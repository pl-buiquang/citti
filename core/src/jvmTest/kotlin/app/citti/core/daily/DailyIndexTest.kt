package app.citti.core.daily

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.db.CittiDatabase
import app.citti.core.insertNew
import app.citti.core.settings.DailyConfig
import app.citti.core.settings.DailySettings
import app.citti.core.settings.ExclusionKind
import app.citti.core.settings.ExclusionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DailyIndexTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { CittiDatabase.Schema.create(it) }
    private val db = CittiDatabase(driver)
    private val notes = db.noteQueries
    private val exclusions = ExclusionRepository(db, Dispatchers.Unconfined)
    private val settings = DailySettings(db, exclusions, Dispatchers.Unconfined)
    private val index = DailyIndex(db, LocaleDetector(db, settings) { "en" }, Dispatchers.Unconfined)

    private fun note(
        title: String,
        folder: String = "journal",
    ): Long {
        notes.insertNew(title, folder, "# $title\n\nText of $title\n", 0, null)
        return notes.lastInsertedLocalId().executeAsOne()
    }

    @Test
    fun dailyNotesOfTheJournalFolderHiddenIncluded() =
        runTest {
            exclusions.add(ExclusionKind.HIDDEN, "journal")
            val first = note("2026-10-30")
            val second = note("2026-10-31")
            note("2026-10-29", folder = "work")

            assertEquals(
                mapOf(
                    LocalDate(2026, 10, 30) to DailyEntry(first, "Text of 2026-10-30"),
                    LocalDate(2026, 10, 31) to DailyEntry(second, "Text of 2026-10-31"),
                ),
                index.flow(DailyConfig()).first(),
            )
        }

    @Test
    fun weekMonthConflictCopyAndDeletedNotesAreLeftOut() =
        runTest {
            val day = note("2026-10-31")
            listOf("2026-W44", "2026-octobre", "2026-Q4", "2026", "2026-10-31 (conflict 2026-11-02)", "2026-10-31 (2)", "Ideas")
                .forEach { note(it) }
            notes.markDeleted(note("2026-10-30"))

            assertEquals(mapOf(LocalDate(2026, 10, 31) to DailyEntry(day, "Text of 2026-10-31")), index.flow(DailyConfig()).first())
        }

    @Test
    fun namesAreReadInEveryDateLocaleUntilOneIsKnown() =
        runTest {
            val fr = note("31 octobre 2026")
            val en = note("1 November 2026")
            val config = DailyConfig(format = "D MMMM YYYY")

            assertEquals(
                setOf(fr, en),
                index
                    .flow(config)
                    .first()
                    .values
                    .map { it.localId }
                    .toSet(),
            )
            assertEquals(
                listOf(fr),
                index
                    .flow(config.copy(localeDetected = "fr"))
                    .first()
                    .values
                    .map { it.localId },
            )
        }

    @Test
    fun theExactTitleWinsWhenTwoTitlesGiveTheSameDate() =
        runTest {
            // Only a format with a time can give two titles for one date; the exact one is at midnight.
            val config = DailyConfig(format = "D-M-YYYY HH")
            note("5-3-2026 14")
            val exact = note("5-3-2026 00")
            note("5-3-2026 09")

            assertEquals(exact, index.flow(config).first()[LocalDate(2026, 3, 5)]?.localId)
        }

    @Test
    fun theMapUpdatesOnInsert() =
        runTest {
            val seen = mutableListOf<Map<LocalDate, DailyEntry>>()
            val collector = launch(UnconfinedTestDispatcher(testScheduler)) { index.flow(DailyConfig()).collect { seen += it } }
            val id = note("2026-10-31")
            note("Ideas")

            assertEquals(listOf(emptyMap(), mapOf(LocalDate(2026, 10, 31) to DailyEntry(id, "Text of 2026-10-31"))), seen)
            collector.cancel()
        }
}
