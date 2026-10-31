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
    fun exclusionsAddNormalizeRemove() {
        val repo = ExclusionRepository(db)
        assertEquals("a/b", repo.add(ExclusionKind.UNSYNCED, " /a//b/ "))
        assertNull(repo.add(ExclusionKind.UNSYNCED, "a/b"))
        assertNull(repo.add(ExclusionKind.UNSYNCED, "  / "))
        assertEquals("a/b", repo.add(ExclusionKind.HIDDEN, "a/b"))
        assertEquals(listOf("a/b"), repo.list(ExclusionKind.UNSYNCED))
        repo.remove(ExclusionKind.UNSYNCED, "a/b")
        assertEquals(emptyList(), repo.list(ExclusionKind.UNSYNCED))
        assertEquals(listOf("a/b"), repo.list(ExclusionKind.HIDDEN))
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
