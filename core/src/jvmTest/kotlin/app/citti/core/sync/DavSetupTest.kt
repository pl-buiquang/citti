package app.citti.core.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.api.NotesApi
import app.citti.core.api.NotesApiConfig
import app.citti.core.api.createNotesHttpClient
import app.citti.core.dav.DavRoot
import app.citti.core.dav.DavSetup
import app.citti.core.dav.KEY_DAV_NOTES_PATH
import app.citti.core.dav.KEY_DAV_USER_ID
import app.citti.core.db.CittiDatabase
import app.citti.core.fake.Endpoint
import app.citti.core.fake.FakeFailure
import app.citti.core.fake.FakeNotesServer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DavSetupTest {
    private val server = FakeNotesServer().apply { userId = "u-123" }
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val db: CittiDatabase = CittiDatabase.Schema.create(driver).let { CittiDatabase(driver) }
    private val api =
        NotesApi(
            createNotesHttpClient(server.engine, NotesApiConfig("https://host/", "alice@example.com", "x")),
            NotesApiConfig("https://host/", "alice@example.com", "x"),
        )
    private val setup = DavSetup(api, db, "https://host/")
    private val engine = SyncEngine(PushSync(api, db) { 1L }, PullSync(api, db), db, dav = setup) { 1L }

    @Test
    fun theFirstSyncStoresTheUserIdAndNotesPath() =
        runTest {
            server.notesPath = "My Notes"
            assertNull(setup.root())
            engine.sync(SyncTrigger.Manual)
            assertEquals(DavRoot("https://host/", "u-123", "My Notes"), setup.root())
            assertEquals("u-123", db.settingQueries.selectSetting(KEY_DAV_USER_ID).executeAsOne())
            assertEquals("My Notes", db.settingQueries.selectSetting(KEY_DAV_NOTES_PATH).executeAsOne())
        }

    @Test
    fun onceAnAppOpenAndNotOnOtherTriggersOnceStored() =
        runTest {
            engine.sync(SyncTrigger.AppOpen)
            assertEquals(1, server.count(Endpoint.CLOUD_USER))
            assertEquals(1, server.count(Endpoint.SETTINGS))
            engine.sync(SyncTrigger.Periodic)
            assertEquals(1, server.count(Endpoint.CLOUD_USER))
            engine.sync(SyncTrigger.AppOpen)
            assertEquals(2, server.count(Endpoint.CLOUD_USER))
            assertEquals(2, server.count(Endpoint.SETTINGS))
        }

    @Test
    fun aNoChangeSyncStaysOneListRequest() =
        runTest {
            server.generateNotes(5)
            engine.sync(SyncTrigger.AppOpen)
            server.clearRequests()
            assertEquals(SyncOutcome.NothingChanged, engine.sync(SyncTrigger.Periodic))
            assertEquals(1, server.requests.size)
        }

    @Test
    fun failuresAreIgnoredAndKeepTheStoredValues() =
        runTest {
            engine.sync(SyncTrigger.AppOpen)
            server.failNext(FakeFailure.SERVER_ERROR, endpoint = Endpoint.CLOUD_USER)
            server.failNext(FakeFailure.NETWORK_ERROR, endpoint = Endpoint.SETTINGS)
            server.notesPath = "Changed"
            engine.sync(SyncTrigger.AppOpen)
            assertEquals(DavRoot("https://host/", "u-123", "Notes"), setup.root())
        }

    @Test
    fun aUserIdThatIsNotOneSegmentIsNeverStored() =
        runTest {
            server.userId = ".."
            engine.sync(SyncTrigger.AppOpen)
            assertNull(setup.root())
            assertNull(db.settingQueries.selectSetting(KEY_DAV_USER_ID).executeAsOneOrNull())
            server.userId = "u-123"
            engine.sync(SyncTrigger.AppOpen)
            server.userId = "u/../other"
            engine.sync(SyncTrigger.AppOpen)
            assertEquals(DavRoot("https://host/", "u-123", "Notes"), setup.root())
        }

    @Test
    fun aStoredUserIdThatIsNotValidGivesNoRoot() {
        db.settingQueries.upsertSetting(KEY_DAV_USER_ID, ".")
        db.settingQueries.upsertSetting(KEY_DAV_NOTES_PATH, "Notes")
        assertNull(setup.root())
    }
}
