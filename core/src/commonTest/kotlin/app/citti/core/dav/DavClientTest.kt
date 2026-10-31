package app.citti.core.dav

import app.citti.core.api.ApiError
import app.citti.core.api.ApiResult
import app.citti.core.api.NotesApiConfig
import app.citti.core.api.createNotesHttpClient
import app.citti.core.fake.Endpoint
import app.citti.core.fake.FakeFailure
import app.citti.core.fake.FakeNotesServer
import app.citti.core.fake.HeaderCasing
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DavClientTest {
    private val server = FakeNotesServer(credentials = "alice@example.com" to "s3cret")
    private var root: DavRoot? = DavRoot("https://host/", "alice", "Notes")

    private fun client(): DavClient {
        val config = NotesApiConfig("https://host/", "alice@example.com", "s3cret")
        return DavClient(createNotesHttpClient(server.engine, config), { root })
    }

    private fun path(p: String) = assertNotNull(VaultPath.parse(p))

    @Test
    fun urlsEncodeEverySegment() {
        val r = DavRoot("https://host/cloud/", "alice@example.com", "My Notes/sub")
        assertEquals(
            "https://host/cloud/remote.php/dav/files/alice%40example.com/My%20Notes/sub/media/Screenshot%20from%202025-01-15%2011-51-18.png",
            r.url(path("media/Screenshot from 2025-01-15 11-51-18.png")),
        )
        assertEquals(
            "https://host/remote.php/dav/files/u/N/%C3%A9t%C3%A9/a%20b.png",
            DavRoot("https://host", "u", "N").url(path("été/a b.png")),
        )
        assertEquals(
            "https://host/remote.php/dav/files/u/N/a%23b/c%3Fd/100%25.png",
            DavRoot("https://host", "u", "N").url(path("a#b/c?d/100%.png")),
        )
    }

    @Test
    fun aNotesPathNeverLeavesTheNotesFolder() {
        val url = DavRoot("https://host/", "alice", "Notes/../Documents").url(path("a.png"))
        assertEquals("https://host/remote.php/dav/files/alice/Notes/Documents/a.png", url)
        assertFalse(url.split('/').any { it == ".." || it == "." })
        assertEquals(
            "https://host/remote.php/dav/files/alice/a.png",
            DavRoot("https://host/", "alice", "./../..//").url(path("a.png")),
        )
    }

    @Test
    fun noRequestForAUserIdThatIsNotOneSegment() =
        runTest {
            server.putFile("a.png", byteArrayOf(1))
            for (id in listOf("..", ".", "", "alice/../bob", "a/b")) {
                assertFalse(DavRoot.isValidUserId(id), id)
                root = DavRoot("https://host/", id, "Notes")
                assertEquals(ApiError.DavNotReady, assertIs<ApiResult.Failure>(client().get(path("a.png"))).error)
            }
            assertEquals(0, server.requests.size)
            assertTrue(DavRoot.isValidUserId("alice@example.com"))
            assertTrue(DavRoot.isValidUserId("a..b"))
        }

    @Test
    fun getsABodyWithItsEtag() =
        runTest {
            server.userId = "alice"
            server.putFile("media/a b.png", byteArrayOf(1, 2, 3))
            val body = assertIs<DavGet.Body>(assertIs<ApiResult.Success<DavGet>>(client().get(path("media/a b.png"))).value)
            assertContentEquals(byteArrayOf(1, 2, 3), body.bytes)
            assertEquals(server.fileEtag("media/a b.png"), body.etag)
            val req = server.requests.single()
            assertEquals(Endpoint.DAV_GET, req.endpoint)
            assertTrue(req.header("Authorization")!!.startsWith("Basic "))
        }

    @Test
    fun aLowercaseEtagHeaderIsRead() =
        runTest {
            server.headerCasing = HeaderCasing.LOWERCASE
            server.putFile("a.png", byteArrayOf(9))
            val body = assertIs<DavGet.Body>(assertIs<ApiResult.Success<DavGet>>(client().get(path("a.png"))).value)
            assertNotNull(body.etag)
        }

    @Test
    fun ifNoneMatchGivesNotModified() =
        runTest {
            server.putFile("a.png", byteArrayOf(9))
            val etag = server.fileEtag("a.png")
            val r = assertIs<ApiResult.Success<DavGet>>(client().get(path("a.png"), ifNoneMatch = etag))
            assertEquals(DavGet.NotModified, r.value)
            server.putFile("a.png", byteArrayOf(9, 9))
            assertIs<DavGet.Body>(assertIs<ApiResult.Success<DavGet>>(client().get(path("a.png"), ifNoneMatch = etag)).value)
        }

    @Test
    fun failuresAreValuesNeverThrown() =
        runTest {
            assertEquals(ApiError.NotFound, assertIs<ApiResult.Failure>(client().get(path("nope.png"))).error)
            server.putFile("a.png", byteArrayOf(1))
            server.failNext(FakeFailure.UNAUTHORIZED)
            assertIs<ApiError.Auth>(assertIs<ApiResult.Failure>(client().get(path("a.png"))).error)
            server.failNext(FakeFailure.SERVER_ERROR)
            assertIs<ApiError.Server>(assertIs<ApiResult.Failure>(client().get(path("a.png"))).error)
            server.failNext(FakeFailure.NETWORK_ERROR)
            assertIs<ApiError.Network>(assertIs<ApiResult.Failure>(client().get(path("a.png"))).error)
        }

    @Test
    fun noRequestWithoutARoot() =
        runTest {
            root = null
            assertEquals(ApiError.DavNotReady, assertIs<ApiResult.Failure>(client().get(path("a.png"))).error)
            assertEquals(0, server.requests.size)
            assertNull(root)
        }
}
