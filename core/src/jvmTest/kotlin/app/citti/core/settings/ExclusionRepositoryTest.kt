package app.citti.core.settings

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.daily.DailyGuard
import app.citti.core.db.CittiDatabase
import app.citti.core.note.NameError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Journal folder guard of [ExclusionRepository.add], and [DailyGuard]. */
class ExclusionRepositoryTest {
    private val db: CittiDatabase =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let {
            CittiDatabase.Schema.create(it)
            CittiDatabase(it)
        }
    private val exclusions = ExclusionRepository(db, Dispatchers.Unconfined)
    private val settings = DailySettings(db, exclusions, Dispatchers.Unconfined)
    private val guard = DailyGuard(settings, exclusions)

    @Test
    fun theJournalFolderAndItsParentsAreRefusedAsUnsynced() {
        assertEquals(AddResult.JournalFolder("journal"), exclusions.add(ExclusionKind.UNSYNCED, "journal"))
        assertEquals(AddResult.JournalFolder("journal"), exclusions.add(ExclusionKind.UNSYNCED, " /journal/ "))
        settings.setFolder("notes/journal")
        assertEquals(AddResult.JournalFolder("notes/journal"), exclusions.add(ExclusionKind.UNSYNCED, "notes"))
        assertEquals(AddResult.JournalFolder("notes/journal"), exclusions.add(ExclusionKind.UNSYNCED, "notes/journal"))
        assertEquals(emptyList(), exclusions.list(ExclusionKind.UNSYNCED))
    }

    @Test
    fun aSubFolderOrALookAlikeCanBeUnsyncedAndTheJournalFolderHidden() {
        assertEquals(AddResult.Added("journal/archive"), exclusions.add(ExclusionKind.UNSYNCED, "journal/archive"))
        assertEquals(AddResult.Added("journals"), exclusions.add(ExclusionKind.UNSYNCED, "journals"))
        assertEquals(AddResult.Added("journal"), exclusions.add(ExclusionKind.HIDDEN, "journal"))
        settings.setFolder("other")
        assertEquals(AddResult.Added("journal"), exclusions.add(ExclusionKind.UNSYNCED, "journal"))
    }

    @Test
    fun theGuardReportsAStateSavedBeforeM6() =
        runTest {
            assertNull(guard.conflict().first())
            assertNull(guard.current())
            // Saved before M6: the Journal folder default was never checked against the Unsynced list.
            db.settingQueries.insertExclusion("journal", ExclusionKind.UNSYNCED.name)
            assertEquals("journal", guard.conflict().first())
            assertEquals("journal", guard.current())
            assertEquals(AddResult.Duplicate, exclusions.add(ExclusionKind.UNSYNCED, "journal"))
            assertEquals(FolderResult.Saved("daily"), settings.setFolder("daily"))
            assertNull(guard.conflict().first())
            assertEquals(FolderResult.Refused(NameError.Unsynced), settings.setFolder("journal"))
        }

    @Test
    fun theGuardFollowsTheUnsyncedList() =
        runTest {
            db.settingQueries.insertExclusion("notes", ExclusionKind.UNSYNCED.name)
            db.settingQueries.upsertSetting(KEY_DAILY_FOLDER, "notes/journal")
            assertEquals("notes/journal", guard.conflict().first())
            exclusions.remove(ExclusionKind.UNSYNCED, "notes")
            assertNull(guard.conflict().first())
            db.settingQueries.upsertSetting(KEY_DAILY_FOLDER, "")
            assertEquals(AddResult.Added("notes"), exclusions.add(ExclusionKind.UNSYNCED, "notes"))
            assertNull(guard.current())
        }
}
