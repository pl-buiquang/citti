package app.citti.core.dav

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.api.ApiError
import app.citti.core.api.NotesApiConfig
import app.citti.core.api.createNotesHttpClient
import app.citti.core.db.CittiDatabase
import app.citti.core.fake.Endpoint
import app.citti.core.fake.FakeFailure
import app.citti.core.fake.FakeNotesServer
import app.citti.core.settings.ImageSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ImageUploaderTest {
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
    private val settings = ImageSettings(db)
    private val resolver = ImageResolver(dav, config)
    private val uploader = ImageUploader(dav, config, settings, resolver)

    private fun appJson(folder: String) = server.putFile(".obsidian/app.json", """{"attachmentFolderPath":"$folder"}""".encodeToByteArray())

    private suspend fun upload(
        name: String = "a.jpg",
        bytes: ByteArray = byteArrayOf(1, 2, 3),
        noteFolder: String = "",
    ) = assertIs<UploadResult.Uploaded>(uploader.upload(bytes, name, "image/jpeg", noteFolder))

    @Test
    fun withNoAppJsonItGoesToMedia() =
        runTest {
            server.addFolder("media")
            val r = upload()
            assertEquals("media/a.jpg", r.path.value)
            assertEquals("![](a.jpg)", r.link)
            assertContentEquals(byteArrayOf(1, 2, 3), server.file("media/a.jpg"))
        }

    @Test
    fun theFolderComesFromObsidian() =
        runTest {
            appJson("Pictures/")
            server.addFolder("Pictures")
            assertEquals("Pictures/a.jpg", upload().path.value)
        }

    @Test
    fun theSettingOverridesObsidianAndTheLinkIsThenAVaultPath() =
        runTest {
            appJson("Pictures")
            server.addFolder("photos/2026")
            settings.setUploadFolder("/photos/2026/")
            val r = upload()
            assertEquals("photos/2026/a.jpg", r.path.value)
            assertEquals("![](photos/2026/a.jpg)", r.link)
        }

    @Test
    fun theSettingInsideTheAttachmentFolderKeepsARelativeLink() =
        runTest {
            server.addFolder("media/phone")
            settings.setUploadFolder("media/phone")
            assertEquals("![](phone/a.jpg)", upload().link)
        }

    @Test
    fun sameFolderAsTheNoteMeansTheNoteFolder() =
        runTest {
            appJson("./")
            server.addFolder("journal")
            val r = upload(noteFolder = "journal")
            assertEquals("journal/a.jpg", r.path.value)
            assertEquals("![](a.jpg)", r.link)
        }

    @Test
    fun namesAreSanitised() =
        runTest {
            server.addFolder("media")
            assertEquals("media/a_b_c_d_e_f_g_h_i_j.jpg", upload("a/b\\c:d*e?f\"g<h>i|j.jpg").path.value)
            assertEquals("media/x_y.jpg", upload("x\u0007y.jpg").path.value)
            assertEquals("media/image", upload("..").path.value)
            assertEquals("media/image-2", upload("  ").path.value)
        }

    @Test
    fun aTakenNameGetsASuffixBeforeTheExtension() =
        runTest {
            server.putFile("media/a.jpg", byteArrayOf(9))
            server.putFile("media/a-2.jpg", byteArrayOf(9))
            val r = upload()
            assertEquals("media/a-3.jpg", r.path.value)
            assertEquals("![](a-3.jpg)", r.link)
            assertContentEquals(byteArrayOf(9), server.file("media/a.jpg"))
            assertEquals(3, server.count(Endpoint.DAV_PUT))
        }

    @Test
    fun itGivesUpAfterTheTwentiethName() =
        runTest {
            server.putFile("media/a.jpg", byteArrayOf(9))
            for (n in 2..MAX_UPLOAD_SUFFIX) server.putFile("media/a-$n.jpg", byteArrayOf(9))
            val r = assertIs<UploadResult.Failed>(uploader.upload(byteArrayOf(1), "a.jpg", "image/jpeg"))
            assertEquals(ApiError.Http(412), r.error)
            assertEquals(MAX_UPLOAD_SUFFIX, server.count(Endpoint.DAV_PUT))
        }

    @Test
    fun aMissingFolderIsCreatedThenThePutRetried() =
        runTest {
            val r = upload()
            assertEquals("media/a.jpg", r.path.value)
            assertTrue(server.hasFolder("media"))
            assertEquals(
                listOf(Endpoint.DAV_GET, Endpoint.DAV_PUT, Endpoint.DAV_MKCOL, Endpoint.DAV_PUT),
                server.requests.map { it.endpoint },
            )
        }

    @Test
    fun spacesAndParenthesesArePercentEncoded() =
        runTest {
            server.addFolder("media")
            val r = upload("Photo (1) 100%.jpg")
            assertEquals("media/Photo (1) 100%.jpg", r.path.value)
            assertEquals("![](Photo%20%281%29%20100%25.jpg)", r.link)
            assertEquals("a%3Cb%3E.png", ImageUploader.linkTarget(VaultPath.parse("x/a<b>.png")!!, "x"))
            // The link reads back as the file it was made for.
            assertEquals(r.path, VaultPath.resolve("media", "Photo%20%281%29%20100%25.jpg"))
        }

    @Test
    fun theLinkResolvesFromAnyFolderWithNoRequest() =
        runTest {
            server.addFolder("media")
            // A miss earlier in the session does not hide the new file.
            assertEquals(ImageResult.Missing, resolver.open("journal", "a.jpg"))
            val r = upload()
            val target = r.link.removePrefix("![](").removeSuffix(")")
            server.clearRequests()
            for (folder in listOf("", "journal", "a/b")) {
                assertEquals(r.path, resolver.known(folder, target))
                assertEquals(r.path, resolver.offlineCandidates(folder, target).first())
            }
            assertTrue(server.requests.isEmpty())
            assertIs<ImageResult.Loaded>(resolver.open("a/b", target))
            assertEquals(1, server.count(Endpoint.DAV_GET))
        }

    @Test
    fun aFailedUploadIsAValue() =
        runTest {
            server.addFolder("media")
            config.attachmentFolder()
            server.failNext(FakeFailure.NETWORK_ERROR, endpoint = Endpoint.DAV_PUT)
            assertIs<ApiError.Network>(assertIs<UploadResult.Failed>(uploader.upload(byteArrayOf(1), "a.jpg", "image/jpeg")).error)
            server.offline = true
            assertIs<UploadResult.Failed>(uploader.upload(byteArrayOf(1), "a.jpg", "image/jpeg"))
        }
}
