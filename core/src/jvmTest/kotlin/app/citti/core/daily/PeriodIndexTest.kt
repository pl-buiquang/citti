package app.citti.core.daily

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.db.CittiDatabase
import app.citti.core.insertNew
import app.citti.core.settings.DailyConfig
import app.citti.core.settings.DailySettings
import app.citti.core.settings.ExclusionKind
import app.citti.core.settings.ExclusionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PeriodIndexTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { CittiDatabase.Schema.create(it) }
    private val db = CittiDatabase(driver)
    private val notes = db.noteQueries
    private val exclusions = ExclusionRepository(db, Dispatchers.Unconfined)
    private val settings = DailySettings(db, exclusions, Dispatchers.Unconfined)
    private val index = PeriodIndex(db, LocaleDetector(db, settings) { "fr" }, Dispatchers.Unconfined)

    private fun note(
        title: String,
        folder: String = "journal",
    ): Long {
        notes.insertNew(title, folder, "# $title\n\nText of $title\n", 0, null)
        return notes.lastInsertedLocalId().executeAsOne()
    }

    private suspend fun found() = index.flow(DailyConfig()).first()

    @Test
    fun theWeekMonthQuarterAndYearNotesOfTheJournalFolderHiddenIncluded() =
        runTest {
            exclusions.add(ExclusionKind.HIDDEN, "journal")
            val week = note("2026-W44")
            val month = note("2026-octobre")
            val quarter = note("2026-Q4")
            val year = note("2026")
            note("2026-W44", folder = "work")

            assertEquals(
                mapOf(
                    Period.of(PeriodKind.WEEK, LocalDate(2026, 10, 26)) to DailyEntry(week, "Text of 2026-W44"),
                    Period.of(PeriodKind.MONTH, LocalDate(2026, 10, 1)) to DailyEntry(month, "Text of 2026-octobre"),
                    Period.of(PeriodKind.QUARTER, LocalDate(2026, 10, 1)) to DailyEntry(quarter, "Text of 2026-Q4"),
                    Period.of(PeriodKind.YEAR, LocalDate(2026, 1, 1)) to DailyEntry(year, "Text of 2026"),
                ),
                found(),
            )
        }

    @Test
    fun dailyNotesConflictCopiesAndDeletedNotesAreLeftOut() =
        runTest {
            note("2026-10-31")
            note("2026-W44 (conflict 2026-11-02)")
            note("Ideas")
            notes.markDeleted(note("2026-W43"))

            assertEquals(emptyMap(), found())
        }

    @Test
    fun theWeekOfNewYearBelongsToTheYearBefore() =
        runTest {
            val week = note("2026-W53")
            note("2027-W53")

            assertEquals(mapOf(Period.of(PeriodKind.WEEK, LocalDate(2026, 12, 28)) to DailyEntry(week, "Text of 2026-W53")), found())
        }

    @Test
    fun anotherFormatIsRead() =
        runTest {
            settings.setPeriodFormat(PeriodKind.MONTH, "MMMM YYYY")
            val month = note("octobre 2026")
            note("2026-octobre")

            val all = index.flow(settings.config()).first()

            assertEquals(mapOf(Period.of(PeriodKind.MONTH, LocalDate(2026, 10, 1)) to DailyEntry(month, "Text of octobre 2026")), all)
        }

    @Test
    fun neighboursAreOfTheSameKindAndSkipEmptyPeriods() {
        fun entry(id: Long) = DailyEntry(id, null)
        val september = Period.of(PeriodKind.MONTH, LocalDate(2026, 9, 1))
        val october = Period.of(PeriodKind.MONTH, LocalDate(2026, 10, 1))
        val december = Period.of(PeriodKind.MONTH, LocalDate(2026, 12, 1))
        val week = Period.of(PeriodKind.WEEK, LocalDate(2026, 10, 26))
        val all = mapOf(september to entry(1), december to entry(3), week to entry(4))

        val around = all.neighborsOf(october)

        assertEquals(PeriodNeighbor(september, 1), around.previous)
        assertEquals(PeriodNeighbor(december, 3), around.next)
        assertNull(all.neighborsOf(week).previous)
        assertNull(all.neighborsOf(week).next)
    }
}
