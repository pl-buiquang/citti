package app.citti.core.dav

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.api.NotesApiConfig
import app.citti.core.api.createNotesHttpClient
import app.citti.core.db.CittiDatabase
import app.citti.core.fake.Endpoint
import app.citti.core.fake.FakeFailure
import app.citti.core.fake.FakeNotesServer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ImageResolverTest {
    private val server = FakeNotesServer()
    private val db =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let {
            CittiDatabase.Schema.create(it)
            CittiDatabase(it)
        }
    private val dav =
        DavClient(createNotesHttpClient(server.engine, NotesApiConfig("https://host/", "alice", "x")), {
            DavRoot("https://host/", "alice", "Notes")
        })
    private val config = ObsidianConfig(dav, db)
    private val resolver = ImageResolver(dav, config)

    private fun paths(
        folder: String,
        link: String,
        attachments: String? = "media",
    ) = resolver.candidates(folder, link, attachments).map { (it as ImageSource.Vault).path.value }

    @Test
    fun candidatesInOrder() {
        assertEquals(listOf("journal/supernote/a.png", "supernote/a.png", "media/supernote/a.png"), paths("journal", "supernote/a.png"))
        // A Note at the root: the first two are the same.
        assertEquals(listOf("supernote/a.png", "media/supernote/a.png"), paths("", "supernote/a.png"))
        assertEquals(listOf("media/x.png"), paths("a/b", "../../media/x.png"))
        assertEquals(
            listOf("a/Screenshot from x.png", "Screenshot from x.png", "media/Screenshot from x.png"),
            paths("a", "Screenshot%20from%20x.png"),
        )
        assertEquals(listOf("a/x.png", "x.png"), paths("a", "x.png", attachments = null))
        assertEquals(listOf("a/x.png", "x.png", "Attach/x.png"), paths("a", "x.png", "Attach"))
        assertEquals(listOf("a/x.png", "x.png", "a/pics/x.png"), paths("a", "x.png", "./pics"))
    }

    @Test
    fun otherSchemes() {
        assertEquals(listOf(ImageSource.Web("https://x.example/a.png")), resolver.candidates("a", "https://x.example/a.png"))
        assertEquals(listOf(ImageSource.Unsupported), resolver.candidates("a", "file:///x.png"))
        assertEquals(listOf(ImageSource.Unsupported), resolver.candidates("a", "data:image/png;base64,AAA"))
    }

    @Test
    fun theAttachmentFolderComesFromAppJson() =
        runTest {
            server.putFile(".obsidian/app.json", """{"attachmentFolderPath":"Pictures/"}""".encodeToByteArray())
            assertEquals("Pictures", config.attachmentFolder())
            assertEquals("Pictures", db.settingQueries.selectSetting(KEY_OBSIDIAN_ATTACHMENTS).executeAsOne())
        }

    @Test
    fun aMissingAppJsonMeansMedia() =
        runTest {
            assertEquals("media", config.attachmentFolder())
            config.attachmentFolder()
            assertEquals(1, server.count(Endpoint.DAV_GET))
        }

    @Test
    fun sameFolderMeansNoThirdCandidate() =
        runTest {
            server.putFile(".obsidian/app.json", """{"attachmentFolderPath":"./"}""".encodeToByteArray())
            assertEquals(null, config.attachmentFolder())
        }

    @Test
    fun openStopsAtTheFirstHitAndRemembersIt() =
        runTest {
            server.putFile("media/supernote/a.png", byteArrayOf(1))
            val first = assertIs<ImageResult.Loaded>(resolver.open("journal", "supernote/a.png"))
            assertEquals("media/supernote/a.png", first.path.value)
            // app.json, then three candidates: two 404s and a hit.
            assertEquals(4, server.count(Endpoint.DAV_GET))
            server.clearRequests()
            assertIs<ImageResult.Loaded>(resolver.open("journal", "supernote/a.png"))
            assertEquals(1, server.count(Endpoint.DAV_GET))
            assertEquals("media/supernote/a.png", resolver.known("journal", "supernote/a.png")?.value)
        }

    @Test
    fun aMissIsNotAskedAgainInTheSession() =
        runTest {
            server.putFile("media/x.png", byteArrayOf(1))
            resolver.open("a", "x.png")
            server.clearRequests()
            assertIs<ImageResult.Loaded>(resolver.open("b", "x.png"))
            // b/x.png is new, x.png was a miss before: only b/x.png and media/x.png are asked.
            val asked = server.requests.size
            assertEquals(2, asked)
            assertEquals(ImageResult.Missing, resolver.open("a", "none.png"))
            server.clearRequests()
            assertEquals(ImageResult.Missing, resolver.open("a", "none.png"))
            assertEquals(0, server.requests.size)
        }

    @Test
    fun revalidatesTheWinnerWithItsEtag() =
        runTest {
            server.putFile("a/x.png", byteArrayOf(1))
            val loaded = assertIs<ImageResult.Loaded>(resolver.open("a", "x.png"))
            assertEquals(ImageResult.NotModified(loaded.path), resolver.open("a", "x.png", ifNoneMatch = loaded.etag))
        }

    @Test
    fun offlineRecordsNoMiss() =
        runTest {
            server.putFile("a/x.png", byteArrayOf(1))
            resolver.open("a", "x.png") // app.json and the winner
            val other = ImageResolver(dav, config)
            server.failNext(FakeFailure.NETWORK_ERROR)
            assertEquals(ImageResult.Offline, other.open("a", "x.png"))
            assertIs<ImageResult.Loaded>(other.open("a", "x.png"))
        }

    @Test
    fun webAndUnsupportedLinksMakeNoRequest() =
        runTest {
            assertEquals(ImageResult.Web("https://x.example/a.png"), resolver.open("a", "https://x.example/a.png"))
            assertEquals(ImageResult.Unsupported, resolver.open("a", "file:///x.png"))
            assertTrue(server.requests.isEmpty())
        }

    @Test
    fun authFailureIsReported() =
        runTest {
            server.putFile("a/x.png", byteArrayOf(1))
            resolver.open("zzz", "other.png") // loads app.json
            server.failNext(FakeFailure.UNAUTHORIZED)
            assertEquals(ImageResult.AuthFailed, resolver.open("a", "x.png"))
        }
}
