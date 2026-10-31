package app.citti.core.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotesApiTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun api(
        baseUrl: String = "https://host/cloud/",
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): NotesApi {
        val config = NotesApiConfig(baseUrl, "alice", "s3cret")
        val engine =
            MockEngine { request ->
                requests += request
                handler(request)
            }
        return NotesApi(createNotesHttpClient(engine, config), config)
    }

    private fun json(vararg extra: Pair<String, List<String>>) = headersOf(HttpHeaders.ContentType to listOf("application/json"), *extra)

    private fun <T> ApiResult<T>.ok(): T = assertIs<ApiResult.Success<T>>(this).value

    private val listBody =
        """[{"id":1},{"id":2,"etag":"e2","modified":20,"title":"B","category":"a/b","readonly":false,
        |"favorite":true,"internalPath":"a/b/B.md","isShared":false,"shareTypes":[]}]
        """.trimMargin()

    @Test
    fun pageHeadersLowercase() =
        runTest {
            val api =
                api {
                    respond(
                        listBody,
                        HttpStatusCode.OK,
                        json(
                            "etag" to listOf("\"abc\""),
                            "x-notes-chunk-cursor" to listOf("1-2-3"),
                            "x-notes-chunk-pending" to listOf("40"),
                        ),
                    )
                }
            val page = assertIs<ListResult.Page>(api.listNotes().ok())
            assertEquals("\"abc\"", page.etag)
            assertEquals("1-2-3", page.nextCursor)
            assertEquals(40, page.pending)
        }

    @Test
    fun pageHeadersMixedCase() =
        runTest {
            val api =
                api {
                    respond(
                        listBody,
                        HttpStatusCode.OK,
                        json(
                            "ETag" to listOf("\"abc\""),
                            "X-NOTES-Chunk-Cursor" to listOf("1-2-3"),
                            "X-Notes-chunk-Pending" to listOf("40"),
                        ),
                    )
                }
            val page = assertIs<ListResult.Page>(api.listNotes().ok())
            assertEquals("\"abc\"", page.etag)
            assertEquals("1-2-3", page.nextCursor)
            assertEquals(40, page.pending)
        }

    @Test
    fun stubsAndFullItems() =
        runTest {
            val api = api { respond(listBody, HttpStatusCode.OK, json()) }
            val page = assertIs<ListResult.Page>(api.listNotes().ok())
            val (stub, full) = page.notes
            assertTrue(stub.isStub)
            assertNull(stub.title)
            assertFalse(full.isStub)
            assertEquals("B", full.title)
            assertEquals("a/b", full.category)
            assertEquals(20L, full.modified)
            assertEquals(true, full.favorite)
            assertEquals("a/b/B.md", full.internalPath)
        }

    @Test
    fun lastPageHasNoCursor() =
        runTest {
            val api = api { respond(listBody, HttpStatusCode.OK, json("etag" to listOf("\"x\""))) }
            val page = assertIs<ListResult.Page>(api.listNotes(chunkSize = 100).ok())
            assertNull(page.nextCursor)
            assertNull(page.pending)
        }

    @Test
    fun notModified() =
        runTest {
            val api = api { respond("", HttpStatusCode.NotModified) }
            assertEquals(ListResult.NotModified, api.listNotes(ifNoneMatch = "\"abc\"").ok())
            assertEquals("\"abc\"", requests.single().headers[HttpHeaders.IfNoneMatch])
        }

    @Test
    fun listQueryParametersAreSent() =
        runTest {
            val api = api { respond("[]", HttpStatusCode.OK, json()) }
            api.listNotes(pruneBefore = 123, chunkSize = 500, chunkCursor = "c-1-2")
            val params = requests.single().url.parameters
            assertEquals("content", params["exclude"])
            assertEquals("123", params["pruneBefore"])
            assertEquals("500", params["chunkSize"])
            assertEquals("c-1-2", params["chunkCursor"])
        }

    @Test
    fun categoryIsSentEncodedAndEmptyForTheVaultRoot() =
        runTest {
            val api = api { respond("[]", HttpStatusCode.OK, json()) }
            api.listNotes(category = "a b/c&d")
            api.listNotes(category = "")
            api.listNotes()
            assertEquals("a b/c&d", requests[0].url.parameters["category"])
            assertTrue("category=a+b%2Fc%26d" in requests[0].url.encodedQuery || "category=a%20b%2Fc%26d" in requests[0].url.encodedQuery)
            assertEquals("", requests[1].url.parameters["category"])
            assertTrue("category=" in requests[1].url.encodedQuery)
            assertNull(requests[2].url.parameters["category"])
        }

    @Test
    fun listWithContentOmitsExclude() =
        runTest {
            val api = api { respond("[]", HttpStatusCode.OK, json()) }
            api.listNotes(excludeContent = false)
            assertNull(requests.single().url.parameters["exclude"])
        }

    @Test
    fun pathPrefixBaseUrl() =
        runTest {
            val api = api { respond("[]", HttpStatusCode.OK, json()) }
            api.listNotes()
            api.getNote(7)
            api.settings()
            assertEquals(
                listOf(
                    "/cloud/index.php/apps/notes/api/v1/notes",
                    "/cloud/index.php/apps/notes/api/v1/notes/7",
                    "/cloud/index.php/apps/notes/api/v1/settings",
                ),
                requests.map { it.url.encodedPath },
            )
        }

    @Test
    fun baseUrlWithoutTrailingSlash() =
        runTest {
            val api = api(baseUrl = "https://host/cloud") { respond("[]", HttpStatusCode.OK, json()) }
            api.listNotes()
            assertEquals("/cloud/index.php/apps/notes/api/v1/notes", requests.single().url.encodedPath)
        }

    @Test
    fun basicAuthAndUserAgentArePreemptive() =
        runTest {
            val api = api { respond("[]", HttpStatusCode.OK, json()) }
            api.listNotes()
            val headers = requests.single().headers
            // alice:s3cret
            assertEquals("Basic YWxpY2U6czNjcmV0", headers[HttpHeaders.Authorization])
            assertEquals("citti (Android)", headers[HttpHeaders.UserAgent])
        }

    @Test
    fun capabilities() =
        runTest {
            val body =
                """{"ocs":{"meta":{"status":"ok"},"data":{"version":{},"capabilities":{
                |"notes":{"api_version":["0.2","1.3","1.4"],"version":"6.1.0"}}}}}
                """.trimMargin()
            val api = api { respond(body, HttpStatusCode.OK, json()) }
            val caps = api.capabilities().ok()
            assertEquals(listOf("0.2", "1.3", "1.4"), caps.apiVersions)
            assertEquals("6.1.0", caps.appVersion)
            assertTrue(caps.supportsChunks)
            val request = requests.single()
            assertEquals("/cloud/ocs/v2.php/cloud/capabilities", request.url.encodedPath)
            assertEquals("json", request.url.parameters["format"])
            assertEquals("true", request.headers["OCS-APIRequest"])
        }

    @Test
    fun capabilitiesWithoutChunks() {
        assertFalse(Capabilities(listOf("0.2", "1.0", "1.1"), null).supportsChunks)
        assertTrue(Capabilities(listOf("1.2"), null).supportsChunks)
        assertFalse(Capabilities(emptyList(), null).supportsChunks)
    }

    @Test
    fun updateSendsIfMatchAndOnlySetFields() =
        runTest {
            val api =
                api {
                    respond("""{"id":5,"etag":"new","modified":9,"title":"T"}""", HttpStatusCode.OK, json())
                }
            val result = api.updateNote(5, UpdateNote(content = "hi"), ifMatch = "\"old\"").ok()
            assertEquals("new", assertIs<UpdateResult.Updated>(result).note.etag)
            val request = requests.single()
            assertEquals(HttpMethod.Put, request.method)
            assertEquals("\"old\"", request.headers[HttpHeaders.IfMatch])
            assertEquals("""{"content":"hi"}""", (request.body as TextContent).text)
        }

    @Test
    fun updatePreconditionFailed() =
        runTest {
            val api = api { respondError(HttpStatusCode.PreconditionFailed) }
            assertEquals(UpdateResult.PreconditionFailed, api.updateNote(5, UpdateNote(content = "x"), "\"old\"").ok())
        }

    @Test
    fun createPostsNote() =
        runTest {
            val api = api { respond("""{"id":9,"etag":"e","title":"N"}""", HttpStatusCode.OK, json()) }
            assertEquals(9L, api.createNote(NewNote("N", "body", "a/b")).ok().id)
            val request = requests.single()
            assertEquals(HttpMethod.Post, request.method)
            val body = (request.body as TextContent).text
            assertTrue(body.contains(""""title":"N"""") && body.contains(""""category":"a/b""""), body)
        }

    @Test
    fun deleteNotFoundCountsAsDone() =
        runTest {
            val api = api { respondError(HttpStatusCode.NotFound) }
            assertEquals(Deleted, api.deleteNote(3).ok())
        }

    @Test
    fun deleteOk() =
        runTest {
            val api = api { respond("", HttpStatusCode.OK) }
            assertEquals(Deleted, api.deleteNote(3).ok())
            assertEquals(HttpMethod.Delete, requests.single().method)
        }

    @Test
    fun getNoteNotFoundIsAnError() =
        runTest {
            val api = api { respondError(HttpStatusCode.NotFound) }
            val failure = assertIs<ApiResult.Failure>(api.getNote(1))
            assertEquals(ApiError.NotFound, failure.error)
        }

    @Test
    fun settings() =
        runTest {
            val api = api { respond("""{"notesPath":"Notes","fileSuffix":".md"}""", HttpStatusCode.OK, json()) }
            val settings = api.settings().ok()
            assertEquals("Notes", settings.notesPath)
            assertEquals(".md", settings.fileSuffix)
        }

    @Test
    fun unauthorizedMapsToAuth() =
        runTest {
            for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
                val api = api { respondError(status) }
                val failure = assertIs<ApiResult.Failure>(api.listNotes())
                assertEquals(ApiError.Auth(status.value), failure.error)
            }
        }

    @Test
    fun serverErrorMapsToServer() =
        runTest {
            val api = api { respondError(HttpStatusCode.BadGateway) }
            assertEquals(ApiError.Server(502), assertIs<ApiResult.Failure>(api.listNotes()).error)
        }

    @Test
    fun networkFailureMapsToNetwork() =
        runTest {
            val api = api { throw RuntimeException("offline") }
            assertIs<ApiError.Network>(assertIs<ApiResult.Failure>(api.listNotes()).error)
        }

    @Test
    fun garbageBodyMapsToBadResponse() =
        runTest {
            val api = api { respond("<html>", HttpStatusCode.OK, json()) }
            assertIs<ApiError.BadResponse>(assertIs<ApiResult.Failure>(api.listNotes()).error)
        }
}
