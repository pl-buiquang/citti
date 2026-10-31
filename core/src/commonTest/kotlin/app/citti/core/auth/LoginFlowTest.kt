package app.citti.core.auth

import app.citti.core.api.ApiError
import app.citti.core.api.ApiResult
import app.citti.core.api.createNotesHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LoginFlowTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun flow(handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): LoginFlow {
        val engine =
            MockEngine { request ->
                requests += request
                handler(request)
            }
        return LoginFlow(
            client = HttpClient(engine) { expectSuccess = false },
            apiClientFactory = { createNotesHttpClient(engine, it) },
        )
    }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private val startBody =
        """{"poll":{"token":"tok","endpoint":"https://host/cloud/login/v2/poll"},"login":"https://host/cloud/login/v2/flow/abc"}"""

    private val approvedBody = """{"server":"https://real.example/cloud","loginName":"alice","appPassword":"s3cret"}"""

    private val session = LoginSession("https://host/login", "tok", "https://host/cloud/login/v2/poll")

    private fun <T> ApiResult<T>.ok(): T = assertIs<ApiResult.Success<T>>(this).value

    private fun <T> ApiResult<T>.error(): ApiError = assertIs<ApiResult.Failure>(this).error

    @Test
    fun startParsesSessionAndSendsUserAgent() =
        runTest {
            val session = flow { respond(startBody, HttpStatusCode.OK, jsonHeaders) }.start("host/cloud/").ok()
            assertEquals(LoginSession("https://host/cloud/login/v2/flow/abc", "tok", "https://host/cloud/login/v2/poll"), session)
            val request = requests.single()
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("https://host/cloud/index.php/login/v2", request.url.toString())
            assertEquals("citti (Android)", request.headers[HttpHeaders.UserAgent])
        }

    @Test
    fun startRejectsBadInput() =
        runTest {
            assertEquals(ApiError.InvalidUrl, flow { respond("", HttpStatusCode.OK) }.start("  ").error())
            assertIs<ApiError.BadResponse>(flow { respond("{}", HttpStatusCode.OK) }.start("host").error())
            assertIs<ApiError.BadResponse>(flow { respond("nope", HttpStatusCode.OK) }.start("host").error())
            assertEquals(ApiError.Http(404), flow { respond("", HttpStatusCode.NotFound) }.start("host").error())
        }

    @Test
    fun normalizesUrls() {
        assertEquals("https://cloud.example.com", normalizeServerUrl("cloud.example.com"))
        assertEquals("https://cloud.example.com", normalizeServerUrl("  https://cloud.example.com/// "))
        assertEquals("http://10.0.0.2:8080/cloud", normalizeServerUrl("http://10.0.0.2:8080/cloud/"))
        assertEquals("https://host/cloud", normalizeServerUrl("host/cloud"))
        assertNull(normalizeServerUrl(""))
        assertNull(normalizeServerUrl("https://"))
    }

    @Test
    fun pollsUntilApproved() =
        runTest {
            var calls = 0
            val flow =
                flow {
                    if (++calls < 3) respond("", HttpStatusCode.NotFound) else respond(approvedBody, HttpStatusCode.OK, jsonHeaders)
                }
            val result = flow.awaitApproval(session)
            assertEquals(LoginResult.Approved("https://real.example/cloud", "alice", "s3cret"), result)
            assertEquals(3, requests.size)
            assertEquals(4000, currentTime)
            val first = requests.first()
            assertEquals(HttpMethod.Post, first.method)
            assertEquals("https://host/cloud/login/v2/poll", first.url.toString())
            assertEquals("token=tok", (first.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString())
            assertEquals("citti (Android)", first.headers[HttpHeaders.UserAgent])
        }

    @Test
    fun timesOut() =
        runTest {
            val result = flow { respond("", HttpStatusCode.NotFound) }.awaitApproval(session, intervalMs = 2000, timeoutMs = 10_000)
            assertEquals(LoginResult.TimedOut, result)
            assertEquals(10_000, currentTime)
        }

    @Test
    fun networkErrorsKeepWaiting() =
        runTest {
            var calls = 0
            val flow =
                flow {
                    if (++calls == 1) throw kotlinx.io.IOException("offline") else respond(approvedBody, HttpStatusCode.OK, jsonHeaders)
                }
            assertIs<LoginResult.Approved>(flow.awaitApproval(session))
        }

    @Test
    fun unexpectedStatusFails() =
        runTest {
            val result = flow { respond("", HttpStatusCode.InternalServerError) }.awaitApproval(session)
            assertEquals(LoginResult.Failed(ApiError.Server(500)), result)
        }

    @Test
    fun cancellable() =
        runTest {
            val flow = flow { respond("", HttpStatusCode.NotFound) }
            val job = launch { flow.awaitApproval(session) }
            testScheduler.advanceTimeBy(5000)
            testScheduler.runCurrent()
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
        }

    @Test
    fun approvedDoesNotLeakPassword() {
        assertTrue("s3cret" !in LoginResult.Approved("https://x", "alice", "s3cret").toString())
        assertTrue("s3cret" !in Credentials("https://x", "alice", "s3cret").toString())
    }

    private fun capsBody(notes: String) = """{"ocs":{"meta":{"status":"ok"},"data":{"capabilities":{$notes}}}}"""

    private val notesCaps = capsBody(""""notes":{"api_version":["1.3","1.4"],"version":"4.10.1"}""")

    @Test
    fun manualVerifySuccess() =
        runTest {
            val flow = flow { respond(notesCaps, HttpStatusCode.OK, jsonHeaders) }
            val verified = flow.verifyManual("host/cloud/", "alice", "s3cret").ok()
            assertEquals(Credentials("https://host/cloud", "alice", "s3cret"), verified.credentials)
            assertTrue(verified.supportsChunks)
            assertEquals("4.10.1", verified.capabilities.appVersion)
            assertEquals("https://host/cloud/ocs/v2.php/cloud/capabilities?format=json", requests.single().url.toString())
        }

    @Test
    fun manualVerifyUnauthorized() =
        runTest {
            val flow = flow { respond("", HttpStatusCode.Unauthorized) }
            assertEquals(ApiError.Auth(401), flow.verifyManual("host", "alice", "bad").error())
        }

    @Test
    fun manualVerifyNotesAppMissing() =
        runTest {
            val flow = flow { respond(capsBody(""""files":{}"""), HttpStatusCode.OK, jsonHeaders) }
            assertEquals(ApiError.NotesAppMissing, flow.verifyManual("host", "alice", "pw").error())
        }

    @Test
    fun finishChecksTheServerFromTheResponse() =
        runTest {
            val flow = flow { respond(notesCaps, HttpStatusCode.OK, jsonHeaders) }
            val verified = flow.finish(LoginResult.Approved("https://real.example/cloud", "alice", "pw")).ok()
            assertEquals("https://real.example/cloud", verified.credentials.serverUrl)
            assertEquals("real.example", requests.single().url.host)
        }
}
