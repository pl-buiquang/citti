package app.citti.core.fake

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeNotesServerTest {
    private val api = "/index.php/apps/notes/api/v1"

    private fun client(server: FakeNotesServer) = HttpClient(server.engine)

    private suspend fun HttpResponse.array(): JsonArray = Json.parseToJsonElement(bodyAsText()).jsonArray

    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonArray.ids() =
        map {
            it.jsonObject
                .getValue("id")
                .jsonPrimitive.content
                .toLong()
        }

    @Test
    fun conditionalListReturns304WhenEtagMatches() =
        runTest {
            val server =
                FakeNotesServer().apply {
                    addNote("A", "a")
                    addNote("B", "b")
                }
            val http = client(server)
            val first = http.get("$api/notes?exclude=content")
            assertEquals(HttpStatusCode.OK, first.status)
            val etag = first.headers["ETag"]!!
            assertTrue(etag.startsWith("\"") && etag.endsWith("\""))
            assertContains(first.headers[HttpHeaders.LastModified]!!, "2027")
            assertFalse(
                first
                    .array()
                    .first()
                    .jsonObject
                    .containsKey("content"),
            )

            val second = http.get("$api/notes?exclude=content") { header("If-None-Match", etag) }
            assertEquals(HttpStatusCode.NotModified, second.status)
            assertEquals("", second.bodyAsText())

            // The ETag is per query shape.
            val other = http.get("$api/notes") { header("If-None-Match", etag) }
            assertEquals(HttpStatusCode.OK, other.status)
            assertNotEquals(etag, other.headers["ETag"])

            // An edit changes the set, so the old ETag no longer matches.
            server.editOnServer(1, content = "changed")
            val third = http.get("$api/notes?exclude=content") { header("If-None-Match", etag) }
            assertEquals(HttpStatusCode.OK, third.status)
            assertEquals(4, server.count(Endpoint.LIST))
        }

    @Test
    fun categoryFilterMatchesTheExactFolderOnly() =
        runTest {
            val server = FakeNotesServer()
            val root = server.addNote("Root")
            val a = server.addNote("A", category = "scratch")
            server.addNote("Sub", category = "scratch/sub")
            server.addNote("Other", category = "scratchpad")
            val http = client(server)

            assertEquals(listOf(a), http.get("$api/notes?exclude=content&category=scratch").array().ids())
            // An empty value is the Vault root only, not every Note.
            assertEquals(listOf(root), http.get("$api/notes?exclude=content&category=").array().ids())
            assertEquals(4, http.get("$api/notes?exclude=content").array().size)
        }

    @Test
    fun categoryListHasItsOwnEtagAndAnswers304() =
        runTest {
            val server = FakeNotesServer()
            val a = server.addNote("A", category = "scratch")
            val other = server.addNote("B", category = "journal")
            val http = client(server)
            val full = http.get("$api/notes?exclude=content").headers["ETag"]!!
            val first = http.get("$api/notes?exclude=content&category=scratch")
            val etag = first.headers["ETag"]!!
            assertNotEquals(full, etag)

            val second = http.get("$api/notes?exclude=content&category=scratch") { header("If-None-Match", etag) }
            assertEquals(HttpStatusCode.NotModified, second.status)

            // A change in another Folder leaves this Folder's ETag alone.
            server.editOnServer(other, content = "changed")
            val third = http.get("$api/notes?exclude=content&category=scratch") { header("If-None-Match", etag) }
            assertEquals(HttpStatusCode.NotModified, third.status)

            server.editOnServer(a, content = "changed")
            val fourth = http.get("$api/notes?exclude=content&category=scratch") { header("If-None-Match", etag) }
            assertEquals(HttpStatusCode.OK, fourth.status)
        }

    @Test
    fun pruneBeforeReturnsStubsForOldNotes() =
        runTest {
            val server = FakeNotesServer()
            val old = server.addNote("Old", modified = 100)
            val edge = server.addNote("Edge", modified = 200)
            val new = server.addNote("New", modified = 300)
            val list = client(server).get("$api/notes?exclude=content&pruneBefore=200").array()
            val byId =
                list.associateBy {
                    it.jsonObject
                        .getValue("id")
                        .jsonPrimitive.content
                        .toLong()
                }
            assertEquals(setOf(old, edge, new), byId.keys)
            assertEquals(setOf("id"), byId.getValue(old).jsonObject.keys)
            assertEquals(setOf("id"), byId.getValue(edge).jsonObject.keys)
            assertEquals(
                "New",
                byId
                    .getValue(new)
                    .jsonObject
                    .getValue("title")
                    .jsonPrimitive.content,
            )

            server.deleteOnServer(old)
            val after = client(server).get("$api/notes?exclude=content&pruneBefore=200").array()
            assertEquals(setOf(edge, new), after.ids().toSet())
        }

    @Test
    fun chunkedListReachesAllNotesWithPending() =
        runTest {
            val server = FakeNotesServer()
            server.generateNotes(count = 25)
            val http = client(server)

            val fullIds = mutableSetOf<Long>()
            var cursor: String? = null
            val pendings = mutableListOf<Int?>()
            val cursors = mutableListOf<String?>()
            do {
                val url = "$api/notes?exclude=content&chunkSize=10" + (cursor?.let { "&chunkCursor=$it" } ?: "")
                val response = http.get(url)
                assertEquals(HttpStatusCode.OK, response.status)
                response.array().forEach {
                    val o = it.jsonObject
                    if (o.size > 1) {
                        fullIds +=
                            o
                                .getValue("id")
                                .jsonPrimitive.content
                                .toLong()
                    }
                }
                cursor = response.headers["X-Notes-Chunk-Cursor"]
                cursors += cursor
                pendings += response.headers["X-Notes-Chunk-Pending"]?.toInt()
            } while (cursor != null)

            assertEquals(25, fullIds.size)
            assertEquals(listOf(15, 5, null), pendings)
            assertNull(cursors.last())
            val parts = cursors.first()!!.split('-')
            assertEquals(3, parts.size)
            assertEquals(server.now.toString(), parts[0])

            // The second page repeats the first page as stubs, then 10 new notes in full.
            val second = http.get("$api/notes?exclude=content&chunkSize=10&chunkCursor=${cursors.first()}").array()
            assertEquals(20, second.size)
            assertEquals(10, second.count { it.jsonObject.size == 1 })

            // A matching ETag does not give a 304 for chunked lists.
            val chunk = http.get("$api/notes?exclude=content&chunkSize=100")
            val again = http.get("$api/notes?exclude=content&chunkSize=100") { header("If-None-Match", chunk.headers["ETag"]!!) }
            assertEquals(HttpStatusCode.OK, again.status)
            assertNull(chunk.headers["X-Notes-Chunk-Cursor"])
            assertNull(chunk.headers["X-Notes-Chunk-Pending"])
        }

    @Test
    fun updateHonorsIfMatchAndBumpsEtag() =
        runTest {
            val server = FakeNotesServer()
            val id = server.addNote("T", "v1", modified = 10)
            val http = client(server)
            val etag =
                http
                    .get("$api/notes/$id")
                    .obj()
                    .getValue("etag")
                    .jsonPrimitive.content

            val ok =
                http.put("$api/notes/$id") {
                    header("If-Match", "\"$etag\"")
                    setBody("""{"content":"v2"}""")
                }
            assertEquals(HttpStatusCode.OK, ok.status)
            val updated = ok.obj()
            assertNotEquals(etag, updated.getValue("etag").jsonPrimitive.content)
            assertEquals(
                server.now,
                updated
                    .getValue("modified")
                    .jsonPrimitive.content
                    .toLong(),
            )
            assertEquals("v2", server.note(id)!!.content)

            val stale =
                http.put("$api/notes/$id") {
                    header("If-Match", "\"$etag\"")
                    setBody("""{"content":"v3"}""")
                }
            assertEquals(HttpStatusCode.PreconditionFailed, stale.status)
            assertEquals("v2", server.note(id)!!.content)

            server.editOnServer(id, content = "other client")
            val afterEdit = http.get("$api/notes/$id").obj()
            assertNotEquals(updated.getValue("etag"), afterEdit.getValue("etag"))
        }

    @Test
    fun deleteHonorsIfMatch() =
        runTest {
            val server = FakeNotesServer()
            val id = server.addNote("T", "v1")
            val http = client(server)
            val stale = server.note(id)!!.etag
            server.editOnServer(id, content = "v2")

            val refused = http.delete("$api/notes/$id") { header("If-Match", "\"$stale\"") }
            assertEquals(HttpStatusCode.PreconditionFailed, refused.status)
            assertEquals("v2", server.note(id)!!.content)

            val ok = http.delete("$api/notes/$id") { header("If-Match", "\"${server.note(id)!!.etag}\"") }
            assertEquals(HttpStatusCode.OK, ok.status)
            assertNull(server.note(id))
        }

    @Test
    fun createGetAndDeleteWork() =
        runTest {
            val server = FakeNotesServer()
            val http = client(server)
            val created = http.post("$api/notes") { setBody("""{"title":"Hi","content":"body","category":"a/b","favorite":true}""") }.obj()
            val id =
                created
                    .getValue("id")
                    .jsonPrimitive.content
                    .toLong()
            assertEquals("a/b", server.note(id)!!.category)
            assertTrue(server.note(id)!!.favorite)

            assertEquals(HttpStatusCode.OK, http.delete("$api/notes/$id").status)
            assertNull(server.note(id))
            assertEquals(HttpStatusCode.NotFound, http.delete("$api/notes/$id").status)
            assertEquals(HttpStatusCode.NotFound, http.get("$api/notes/$id").status)
            assertEquals(HttpStatusCode.NotFound, http.put("$api/notes/999") { setBody("{}") }.status)
        }

    @Test
    fun createRenamesDuplicateTitleInTheSameFolder() =
        runTest {
            val server = FakeNotesServer()
            val http = client(server)

            suspend fun create(
                title: String,
                category: String,
            ) = http
                .post("$api/notes") { setBody("""{"title":"$title","content":"","category":"$category"}""") }
                .obj()
                .getValue("title")
                .jsonPrimitive.content

            assertEquals("Hi", create("Hi", "a"))
            assertEquals("Hi (2)", create("Hi", "a"))
            assertEquals("Hi (3)", create("Hi", "a"))
            assertEquals("Hi", create("Hi", "b"))
        }

    @Test
    fun createTitleMatchIsCaseSensitive() =
        runTest {
            val server = FakeNotesServer()
            server.addNote("X CASE", category = "a")
            val created = client(server).post("$api/notes") { setBody("""{"title":"X case","content":"","category":"a"}""") }.obj()
            assertEquals("X case", created.getValue("title").jsonPrimitive.content)
        }

    @Test
    fun updateOntoATakenTitleRenamesToTheNextFreeOne() =
        runTest {
            val server = FakeNotesServer()
            server.addNote("T", category = "a")
            server.addNote("T (2)", category = "a")
            server.addNote("T", category = "b")
            val renamed = server.addNote("U", category = "a")
            val moved = server.addNote("T", category = "c")
            val http = client(server)

            suspend fun put(
                id: Long,
                body: String,
            ) = http
                .put("$api/notes/$id") { setBody(body) }
                .also { assertEquals(HttpStatusCode.OK, it.status) }
                .obj()
                .getValue("title")
                .jsonPrimitive.content

            // Never refused, never overwritten: the next free `Title (n)`, as on the real server.
            assertEquals("T (3)", put(renamed, """{"title":"T"}"""))
            assertEquals("T (2)", put(moved, """{"category":"b"}"""))
            assertEquals(5, server.notes.size)
            // A PUT that keeps the title and folder keeps the title.
            assertEquals("T (3)", put(renamed, """{"title":"T (3)","content":"x"}"""))
        }

    @Test
    fun categoryListsExactlyThatFolderWithItsOwnEtag() =
        runTest {
            val server = FakeNotesServer()
            val root = server.addNote("R")
            val a = server.addNote("A", category = "a")
            server.addNote("Sub", category = "a/b")
            val http = client(server)

            val folder = http.get("$api/notes?category=a&exclude=content")
            assertEquals(listOf(a), folder.array().ids())
            assertEquals(listOf(root), http.get("$api/notes?category=&exclude=content").array().ids())

            val etag = folder.headers["ETag"]!!
            assertNotEquals(etag, http.get("$api/notes?exclude=content").headers["ETag"])
            val again = http.get("$api/notes?category=a&exclude=content") { header("If-None-Match", etag) }
            assertEquals(HttpStatusCode.NotModified, again.status)
        }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun requiresBasicCredentials() =
        runTest {
            val server = FakeNotesServer(credentials = "paul" to "secret")
            val http = client(server)
            assertEquals(HttpStatusCode.Unauthorized, http.get("$api/notes").status)
            assertEquals(
                HttpStatusCode.Unauthorized,
                http.get("$api/notes") { header("Authorization", "Basic " + Base64.encode("paul:wrong".encodeToByteArray())) }.status,
            )
            val ok = http.get("$api/notes") { header("Authorization", "Basic " + Base64.encode("paul:secret".encodeToByteArray())) }
            assertEquals(HttpStatusCode.OK, ok.status)
        }

    @Test
    fun headerCasingIsConfigurable() =
        runTest {
            val server = FakeNotesServer(headerCasing = HeaderCasing.LOWERCASE)
            server.generateNotes(count = 5)
            val response = client(server).get("$api/notes?exclude=content&chunkSize=2")
            val names = response.headers.names()
            assertContains(names, "etag")
            assertContains(names, "last-modified")
            assertContains(names, "x-notes-chunk-cursor")
            assertContains(names, "x-notes-chunk-pending")
            assertFalse(names.contains("ETag"))

            server.headerCasing = HeaderCasing.CANONICAL
            val canonical = client(server).get("$api/notes?exclude=content&chunkSize=2").headers.names()
            assertContains(canonical, "ETag")
            assertContains(canonical, "X-Notes-Chunk-Cursor")
        }

    @Test
    fun basePathPrefixIsHonored() =
        runTest {
            val server = FakeNotesServer(basePath = "/cloud")
            server.addNote("A")
            val http = client(server)
            assertEquals(HttpStatusCode.OK, http.get("/cloud$api/notes").status)
            assertEquals(HttpStatusCode.OK, http.get("/cloud$api/settings").status)
            assertEquals(HttpStatusCode.OK, http.get("/cloud/ocs/v2.php/cloud/capabilities").status)
            assertEquals(HttpStatusCode.NotFound, http.get("$api/notes").status)
        }

    @Test
    fun settingsAndCapabilities() =
        runTest {
            val server = FakeNotesServer()
            val http = client(server)
            val settings = http.get("$api/settings").obj()
            assertEquals("Notes", settings.getValue("notesPath").jsonPrimitive.content)
            assertEquals(".md", settings.getValue("fileSuffix").jsonPrimitive.content)

            val notes =
                http
                    .get("/ocs/v2.php/cloud/capabilities") { header("OCS-APIRequest", "true") }
                    .obj()
                    .getValue("ocs")
                    .jsonObject
                    .getValue("data")
                    .jsonObject
                    .getValue("capabilities")
                    .jsonObject
                    .getValue("notes")
                    .jsonObject
            assertEquals(listOf("0.2", "1.3", "1.4"), notes.getValue("api_version").jsonArray.map { it.jsonPrimitive.content })
            assertEquals("6.1.0", notes.getValue("version").jsonPrimitive.content)
        }

    @Test
    fun injectedFailuresAreConsumedInOrder() =
        runTest {
            val server = FakeNotesServer()
            val http = client(server)
            server.failNext(FakeFailure.NETWORK_ERROR)
            server.failNext(FakeFailure.SERVER_ERROR)
            server.failNext(FakeFailure.UNAUTHORIZED)
            assertFailsWith<IOException> { http.get("$api/notes") }
            assertEquals(HttpStatusCode.InternalServerError, http.get("$api/notes").status)
            assertEquals(HttpStatusCode.Unauthorized, http.get("$api/notes").status)
            assertEquals(HttpStatusCode.OK, http.get("$api/notes").status)
            assertEquals(listOf(null, 500, 401, 200), server.requests.map { it.status })
        }

    @Test
    fun failNextCanTargetOneEndpoint() =
        runTest {
            val server = FakeNotesServer()
            val id = server.addNote("A", "a")
            val http = client(server)
            server.failNext(FakeFailure.NETWORK_ERROR, times = 2, endpoint = Endpoint.GET_NOTE)
            // The list passes through and leaves the queued failures alone.
            assertEquals(HttpStatusCode.OK, http.get("$api/notes").status)
            assertFailsWith<IOException> { http.get("$api/notes/$id") }
            assertEquals(HttpStatusCode.OK, http.get("$api/notes").status)
            assertFailsWith<IOException> { http.get("$api/notes/$id") }
            assertEquals(HttpStatusCode.OK, http.get("$api/notes/$id").status)
        }

    @Test
    fun requestLogRecordsMethodPathQueryHeadersAndCounts() =
        runTest {
            val server = FakeNotesServer(basePath = "/cloud")
            val id = server.addNote("A")
            val http = client(server)
            http.get("/cloud$api/notes?exclude=content&pruneBefore=5") { header("If-None-Match", "\"x\"") }
            http.get("/cloud$api/notes/$id")
            http.put("/cloud$api/notes/$id") { setBody("{}") }
            http.delete("/cloud$api/notes/$id")

            val log = server.requests
            assertEquals(4, log.size)
            assertEquals(HttpMethod.Get, log[0].method)
            assertEquals("/cloud$api/notes", log[0].path)
            assertEquals(mapOf("exclude" to "content", "pruneBefore" to "5"), log[0].query)
            assertEquals("\"x\"", log[0].header("if-none-match"))
            assertEquals(Endpoint.LIST, log[0].endpoint)
            assertEquals(listOf(Endpoint.LIST, Endpoint.GET_NOTE, Endpoint.UPDATE, Endpoint.DELETE), log.map { it.endpoint })
            assertEquals(1, server.count(Endpoint.UPDATE))
            server.clearRequests()
            assertEquals(0, server.requests.size)
        }

    @Test
    fun generatesVolumeAcrossNestedCategories() =
        runTest {
            val server = FakeNotesServer()
            val ids = server.generateNotes()
            assertEquals(2300, ids.size)
            assertTrue(server.notes.any { it.category.count { c -> c == '/' } == 2 })
            val all = client(server).get("$api/notes?exclude=content&chunkSize=2000").array()
            assertEquals(2000, all.size)
            assertEquals(1, server.notes.count { it.id == 1L && it.favorite })
        }

    private suspend fun HttpClient.poll(endpoint: String) = submitForm(endpoint, parameters { append("token", "fake-login-token") })

    @Test
    fun loginFlowPollsUntilApprovedThenAcceptsTheAppPassword() =
        runTest {
            val server = FakeNotesServer(basePath = "/cloud")
            val http = client(server)
            val start = http.post("$E2E_SERVER/cloud/index.php/login/v2").obj()
            val poll = start.getValue("poll").jsonObject
            val endpoint = poll.getValue("endpoint").jsonPrimitive.content
            assertEquals("$E2E_SERVER/cloud/index.php/login/v2/poll", endpoint)
            assertEquals("fake-login-token", poll.getValue("token").jsonPrimitive.content)
            assertTrue(
                start
                    .getValue("login")
                    .jsonPrimitive.content
                    .startsWith("$E2E_SERVER/cloud/"),
            )

            assertEquals(HttpStatusCode.NotFound, http.poll(endpoint).status)
            server.approveLogin("alice", "app-pw", pollsBeforeSuccess = 2)
            assertEquals(HttpStatusCode.NotFound, http.poll(endpoint).status)
            assertEquals(HttpStatusCode.NotFound, http.poll(endpoint).status)
            val approved = http.poll(endpoint)
            assertEquals(HttpStatusCode.OK, approved.status)
            val body = approved.obj()
            assertEquals("$E2E_SERVER/cloud", body.getValue("server").jsonPrimitive.content)
            assertEquals("alice", body.getValue("loginName").jsonPrimitive.content)
            assertEquals("app-pw", body.getValue("appPassword").jsonPrimitive.content)
            // The token is single use.
            assertEquals(HttpStatusCode.NotFound, http.poll(endpoint).status)

            assertEquals(1, server.count(Endpoint.LOGIN_START))
            assertEquals(5, server.count(Endpoint.LOGIN_POLL))
            assertEquals("alice" to "app-pw", server.credentials)
            assertEquals(HttpStatusCode.Unauthorized, http.get("$E2E_SERVER/cloud$api/notes").status)
        }

    @Test
    fun deniedLoginAnswers403AndAWrongTokenIs404() =
        runTest {
            val server = FakeNotesServer()
            val http = client(server)
            http.post("$E2E_SERVER/index.php/login/v2")
            val endpoint = "$E2E_SERVER/index.php/login/v2/poll"
            assertEquals(
                HttpStatusCode.NotFound,
                http.submitForm(endpoint, parameters { append("token", "other") }).status,
            )
            server.denyLogin()
            assertEquals(HttpStatusCode.Forbidden, http.poll(endpoint).status)
        }

    @Test
    fun offlineThrowsForEveryEndpointAndRecordsANullStatus() =
        runTest {
            val server = FakeNotesServer().apply { addNote("A") }
            val http = client(server)
            server.offline = true
            assertFailsWith<IOException> { http.get("$api/notes") }
            assertFailsWith<IOException> { http.post("/index.php/login/v2") }
            assertFailsWith<IOException> { http.get("/remote.php/dav/files/alice/Notes/a.png") }
            assertEquals(listOf(Endpoint.LIST, Endpoint.LOGIN_START, Endpoint.DAV_GET), server.requests.map { it.endpoint })
            assertTrue(server.requests.all { it.status == null })

            server.offline = false
            assertEquals(HttpStatusCode.OK, http.get("$api/notes").status)
        }

    @Test
    fun requestsKeepTheirBody() =
        runTest {
            val server = FakeNotesServer()
            val id = server.addNote("A", "old")
            client(server).put("$api/notes/$id") { setBody("""{"content":"new"}""") }
            assertEquals("""{"content":"new"}""", server.requests.single().body)
        }

    @Test
    fun awaitRequestReturnsAMatchingRequestOnceItArrives() =
        runTest {
            val server = FakeNotesServer()
            val id = server.addNote("A", "old")
            val http = client(server)
            http.get("$api/notes")
            assertEquals(Endpoint.LIST, server.awaitRequest(Endpoint.LIST).endpoint)

            // MockEngine answers on a real thread, so the waits run on real time.
            withContext(Dispatchers.Default) {
                val pending = async { server.awaitRequest(Endpoint.UPDATE) { "new" in it.body } }
                launch { http.put("$api/notes/$id") { setBody("""{"content":"new"}""") } }
                assertEquals(200, pending.await().status)

                assertFailsWith<TimeoutCancellationException> { server.awaitRequest(Endpoint.DELETE, timeoutMillis = 50) }
            }
        }

    @Test
    fun concurrentRequestsAndTestSideChangesDoNotCorruptState() =
        runTest {
            val server = FakeNotesServer()
            val http = client(server)
            withContext(Dispatchers.Default) {
                (1..200)
                    .map { i ->
                        async {
                            if (i % 2 == 0) server.addNote("N$i") else http.get("$api/notes")
                        }
                    }.awaitAll()
            }
            assertEquals(100, server.notes.size)
            assertEquals(100, server.count(Endpoint.LIST))
            assertEquals(
                100,
                server.notes
                    .map { it.id }
                    .toSet()
                    .size,
            )
        }

    @Test
    fun capabilitiesCanLackTheNotesApp() =
        runTest {
            val server = FakeNotesServer()
            val http = client(server)
            assertContains(http.get("/ocs/v2.php/cloud/capabilities").bodyAsText(), "\"notes\"")
            server.notesAppInstalled = false
            assertFalse("\"notes\"" in http.get("/ocs/v2.php/cloud/capabilities").bodyAsText())
        }
}
