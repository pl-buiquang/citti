package app.citti.core.settings

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.auth.AccountId
import app.citti.core.auth.Credentials
import app.citti.core.auth.accountChanged
import app.citti.core.auth.prepareForSignIn
import app.citti.core.db.CittiDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsTest {
    private val db: CittiDatabase =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let {
            CittiDatabase.Schema.create(it)
            CittiDatabase(it)
        }

    @Test
    fun syncSettingsDefaultsAndRoundTrip() {
        val repo = SyncSettingsRepository(db)
        assertEquals(30, repo.intervalMinutes())
        assertEquals(SyncNetwork.ANY, repo.network())
        repo.setIntervalMinutes(0)
        repo.setNetwork(SyncNetwork.UNMETERED)
        assertEquals(0, repo.intervalMinutes())
        assertEquals(SyncNetwork.UNMETERED, repo.network())
        assertEquals("0", db.settingQueries.selectSetting("sync.interval-minutes").executeAsOne())
        assertEquals("UNMETERED", db.settingQueries.selectSetting("sync.network").executeAsOne())
        assertFailsWith<IllegalArgumentException> { repo.setIntervalMinutes(7) }
    }

    @Test
    fun garbageStoredValuesFallBackToDefaults() {
        db.settingQueries.upsertSetting(KEY_SYNC_INTERVAL_MINUTES, "abc")
        db.settingQueries.upsertSetting(KEY_SYNC_NETWORK, "WIFI")
        val repo = SyncSettingsRepository(db)
        assertEquals(DEFAULT_SYNC_INTERVAL_MINUTES, repo.intervalMinutes())
        assertEquals(SyncNetwork.ANY, repo.network())
    }

    @Test
    fun flowsEmitDefaultThenUpdates() =
        runTest {
            val repo = SyncSettingsRepository(db, Dispatchers.Unconfined)
            assertEquals(30, repo.intervalMinutesFlow().first())
            repo.setIntervalMinutes(120)
            assertEquals(120, repo.intervalMinutesFlow().first())
            repo.setNetwork(SyncNetwork.UNMETERED)
            assertEquals(SyncNetwork.UNMETERED, repo.networkFlow().first())
        }

    @Test
    fun mainModeDefaultsToListAndRoundTrips() =
        runTest {
            val repo = UiSettings(db, Dispatchers.Unconfined)
            assertEquals(MainMode.LIST, repo.mainMode())
            assertEquals(MainMode.LIST, repo.mainModeFlow().first())
            repo.setMainMode(MainMode.TREE)
            assertEquals(MainMode.TREE, repo.mainMode())
            assertEquals(MainMode.TREE, repo.mainModeFlow().first())
            assertEquals("TREE", db.settingQueries.selectSetting(KEY_UI_MAIN_MODE).executeAsOne())
            repo.setMainMode(MainMode.CALENDAR)
            assertEquals(MainMode.CALENDAR, repo.mainMode())
            assertEquals("CALENDAR", db.settingQueries.selectSetting(KEY_UI_MAIN_MODE).executeAsOne())
            db.settingQueries.upsertSetting(KEY_UI_MAIN_MODE, "GRID")
            assertEquals(MainMode.LIST, repo.mainMode())
        }

    @Test
    fun imageCacheSizeDefaultsTo500AndRoundTrips() =
        runTest {
            val repo = ImageSettings(db, Dispatchers.Unconfined)
            assertEquals(500, repo.cacheMb())
            repo.setCacheMb(2000)
            assertEquals(2000, repo.cacheMb())
            assertEquals(2000, repo.cacheMbFlow().first())
            assertEquals("2000", db.settingQueries.selectSetting("images.cache-mb").executeAsOne())
            assertFailsWith<IllegalArgumentException> { repo.setCacheMb(300) }
            db.settingQueries.upsertSetting(KEY_IMAGES_CACHE_MB, "300")
            assertEquals(500, repo.cacheMb())
        }

    @Test
    fun uploadFolderFollowsObsidianUntilSetAndResets() =
        runTest {
            val repo = ImageSettings(db, Dispatchers.Unconfined)
            assertNull(repo.uploadFolder())
            repo.setUploadFolder(" /photos//phone/ ")
            assertEquals("photos/phone", repo.uploadFolder())
            assertEquals("photos/phone", repo.uploadFolderFlow().first())
            assertEquals("photos/phone", db.settingQueries.selectSetting(KEY_IMAGES_UPLOAD_FOLDER).executeAsOne())
            for (bad in listOf("..", "a/../..", "/", "./x")) assertFailsWith<IllegalArgumentException>(bad) { repo.setUploadFolder(bad) }
            assertEquals("photos/phone", repo.uploadFolder())
            // A stored value that leaves the Vault reads as unset.
            db.settingQueries.upsertSetting(KEY_IMAGES_UPLOAD_FOLDER, "../Documents")
            assertNull(repo.uploadFolder())
            repo.setUploadFolder(null)
            assertNull(db.settingQueries.selectSetting(KEY_IMAGES_UPLOAD_FOLDER).executeAsOneOrNull())
        }

    @Test
    fun exclusionsAddNormalizeRemove() {
        val repo = ExclusionRepository(db)
        assertEquals(AddResult.Added("a/b"), repo.add(ExclusionKind.UNSYNCED, " /a//b/ "))
        assertEquals(AddResult.Duplicate, repo.add(ExclusionKind.UNSYNCED, "a/b"))
        assertEquals(AddResult.Blank, repo.add(ExclusionKind.UNSYNCED, "  / "))
        assertEquals(AddResult.Added("a/b"), repo.add(ExclusionKind.HIDDEN, "a/b"))
        assertEquals(listOf("a/b"), repo.list(ExclusionKind.UNSYNCED))
        repo.remove(ExclusionKind.UNSYNCED, "a/b")
        assertEquals(emptyList(), repo.list(ExclusionKind.UNSYNCED))
        assertEquals(listOf("a/b"), repo.list(ExclusionKind.HIDDEN))
    }

    private fun hiddenByRemoteId(): Map<Long, Boolean> =
        db.noteQueries
            .selectAllRemoteIds()
            .executeAsList()
            .filterNotNull()
            .associateWith {
                db.noteQueries
                    .selectByRemoteId(it)
                    .executeAsOne()
                    .hidden
            }

    @Test
    fun hiddenExclusionFlipsHiddenAtOnceWithoutSync() {
        db.noteQueries.upsertFromServer(1, "e", "T", "a/b", false, 1, false, false)
        db.noteQueries.upsertFromServer(2, "e", "T", "a/b/c", false, 1, false, false)
        db.noteQueries.upsertFromServer(3, "e", "T", "a/bc", false, 1, false, false)
        val repo = ExclusionRepository(db)

        assertEquals(AddResult.Added("a/b"), repo.add(ExclusionKind.HIDDEN, "a/b"))
        assertEquals(mapOf(1L to true, 2L to true, 3L to false), hiddenByRemoteId())

        repo.remove(ExclusionKind.HIDDEN, "a/b")
        assertEquals(mapOf(1L to false, 2L to false, 3L to false), hiddenByRemoteId())
    }

    @Test
    fun unsyncedExclusionDoesNotTouchHidden() {
        db.noteQueries.upsertFromServer(1, "e", "T", "a", false, 1, false, false)
        db.noteQueries.upsertFromServer(2, "e", "T", "b", false, 1, false, true)
        val repo = ExclusionRepository(db)

        repo.add(ExclusionKind.UNSYNCED, "a")
        assertEquals(mapOf(1L to false, 2L to true), hiddenByRemoteId())
        repo.remove(ExclusionKind.UNSYNCED, "a")
        assertEquals(mapOf(1L to false, 2L to true), hiddenByRemoteId())
    }

    @Test
    fun accountChangeDecision() {
        val a = AccountId("https://h", "alice")
        assertFalse(accountChanged(null, a))
        assertFalse(accountChanged(a, a))
        assertTrue(accountChanged(a, AccountId("https://h", "bob")))
        assertTrue(accountChanged(a, AccountId("https://other", "alice")))
        assertEquals(AccountId("https://h.example", "alice"), AccountId.of(Credentials("https://H.example/", "alice", "pw")))
    }

    @Test
    fun signInAsOtherAccountWipesButSameAccountKeepsData() {
        db.noteQueries.upsertFromServer(1, "e", "T", "c", false, 1, false, false)
        db.settingQueries.insertExclusion("x", "HIDDEN")
        assertFalse(prepareForSignIn(db, Credentials("https://h/", "alice", "pw")))
        assertEquals(1, db.noteQueries.countNotes().executeAsOne())

        assertFalse(prepareForSignIn(db, Credentials("https://h", "alice", "new-pw")))
        assertEquals(1, db.noteQueries.countNotes().executeAsOne())
        assertEquals(
            1,
            db.settingQueries
                .selectExclusionsByKind("HIDDEN")
                .executeAsList()
                .size,
        )

        assertTrue(prepareForSignIn(db, Credentials("https://h", "bob", "pw")))
        assertEquals(0, db.noteQueries.countNotes().executeAsOne())
        assertEquals(
            0,
            db.settingQueries
                .selectExclusionsByKind("HIDDEN")
                .executeAsList()
                .size,
        )
        // The new owner is recorded after the wipe.
        assertFalse(prepareForSignIn(db, Credentials("https://h", "bob", "pw")))
    }
}
