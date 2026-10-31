package app.citti.core.daily

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.api.ApiError
import app.citti.core.api.NotesApiConfig
import app.citti.core.api.createNotesHttpClient
import app.citti.core.dav.DavClient
import app.citti.core.dav.DavRoot
import app.citti.core.db.CittiDatabase
import app.citti.core.fake.Endpoint
import app.citti.core.fake.FakeFailure
import app.citti.core.fake.FakeNotesServer
import app.citti.core.note.NameError
import app.citti.core.settings.DailyConfig
import app.citti.core.settings.DailySettings
import app.citti.core.settings.ExclusionKind
import app.citti.core.settings.ExclusionRepository
import app.citti.core.settings.FormatResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObsidianDailyImportTest {
    private val server = FakeNotesServer()
    private val db =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let {
            CittiDatabase.Schema.create(it)
            CittiDatabase(it)
        }
    private val exclusions = ExclusionRepository(db, Dispatchers.Unconfined)
    private val settings = DailySettings(db, exclusions, Dispatchers.Unconfined)
    private val dav =
        DavClient(createNotesHttpClient(server.engine, NotesApiConfig("https://host/", "alice", "x")), {
            DavRoot("https://host/", "alice", "Notes")
        })
    private val import = ObsidianDailyImport(dav, settings)

    private fun dailyNotesJson(json: String) = server.putFile(OBSIDIAN_DAILY_NOTES, json.encodeToByteArray())

    @Test
    fun theRealFileSetsFolderAndTemplate() =
        runTest {
            settings.setFolder("daily")
            dailyNotesJson("""{"folder": "journal", "template": "templates/daily"}""")
            val report = import.run()
            assertEquals(mapOf("folder" to "journal", "template" to "templates/daily.md"), report.read)
            assertEquals(emptyList(), report.refused)
            assertFalse(report.notFound)
            assertTrue(report.readSomething)
            val config = settings.config()
            assertEquals("journal", config.folder)
            assertEquals("templates/daily.md", config.template)
            assertEquals("YYYY-MM-DD", config.format)
        }

    @Test
    fun aFormatIsApplied() =
        runTest {
            dailyNotesJson("""{"format": "DD.MM.YYYY", "autorun": true}""")
            val report = import.run()
            assertEquals(mapOf("format" to "DD.MM.YYYY"), report.read)
            assertEquals("DD.MM.YYYY", settings.format())
        }

    @Test
    fun aFormatWithASlashIsRefusedAndReported() =
        runTest {
            settings.setFormat("DD.MM.YYYY")
            dailyNotesJson("""{"folder": "journal", "format": "YYYY/MM/DD"}""")
            val report = import.run()
            assertEquals(listOf<Refusal>(Refusal.Format("YYYY/MM/DD", FormatResult.Slash)), report.refused)
            assertEquals(mapOf("folder" to "journal"), report.read)
            assertEquals("DD.MM.YYYY", settings.format())
        }

    @Test
    fun anUnsyncedFolderIsRefused() =
        runTest {
            exclusions.add(ExclusionKind.UNSYNCED, "private")
            dailyNotesJson("""{"folder": "private/journal", "template": "templates/daily.md"}""")
            val report = import.run()
            assertEquals(listOf<Refusal>(Refusal.Folder("private/journal", NameError.Unsynced)), report.refused)
            assertEquals("journal", settings.folder())
            assertEquals(mapOf("template" to "templates/daily.md"), report.read)
        }

    @Test
    fun aMissingFileKeepsTheDefaults() =
        runTest {
            val report = import.run()
            assertTrue(report.notFound)
            assertFalse(report.readSomething)
            assertEquals(DailyConfig(), settings.config())
        }

    @Test
    fun anUnreadableFileCountsAsNotFound() =
        runTest {
            dailyNotesJson("not json")
            assertTrue(import.run().notFound)
            dailyNotesJson("""{"folder": 3}""")
            val report = import.run()
            assertFalse(report.notFound)
            assertFalse(report.readSomething)
            assertEquals(DailyConfig(), settings.config())
        }

    @Test
    fun aServerErrorIsReported() =
        runTest {
            server.failNext(FakeFailure.SERVER_ERROR, endpoint = Endpoint.DAV_GET)
            val report = import.run()
            assertIs<ApiError>(report.error)
            assertFalse(report.notFound)
        }

    @Test
    fun theAutomaticRunHappensOnce() =
        runTest {
            dailyNotesJson("""{"folder": "journal", "template": "templates/daily"}""")
            val first = import.runOnce()
            assertEquals("templates/daily.md", first?.read?.get("template"))
            assertTrue(settings.importDone())
            assertNull(import.runOnce())
            assertEquals(1, server.count(Endpoint.DAV_GET))
        }

    @Test
    fun theAutomaticRunIsDoneEvenWhenNothingWasFound() =
        runTest {
            assertTrue(import.runOnce()!!.notFound)
            assertTrue(settings.importDone())
            dailyNotesJson("""{"folder": "daily"}""")
            assertNull(import.runOnce())
            assertEquals("journal", settings.folder())
        }

    @Test
    fun concurrentAutomaticRunsImportOnce() =
        runTest {
            dailyNotesJson("""{"folder": "daily"}""")
            val reports = List(3) { async { import.runOnce() } }.awaitAll()
            assertEquals(1, reports.count { it != null })
            assertEquals(1, server.count(Endpoint.DAV_GET))
        }

    @Test
    fun anExplicitRunIgnoresTheFlag() =
        runTest {
            settings.setImportDone()
            dailyNotesJson("""{"folder": "daily"}""")
            assertEquals(mapOf("folder" to "daily"), import.run().read)
        }
}
