package app.citti.core.daily

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeriodTest {
    private val day = LocalDate(2026, 11, 4)

    @Test
    fun aPeriodStartsOnItsFirstDay() {
        assertEquals(LocalDate(2026, 11, 4), Period.of(PeriodKind.DAY, day).start)
        assertEquals(LocalDate(2026, 11, 2), Period.of(PeriodKind.WEEK, day).start)
        assertEquals(LocalDate(2026, 11, 1), Period.of(PeriodKind.MONTH, day).start)
        assertEquals(LocalDate(2026, 10, 1), Period.of(PeriodKind.QUARTER, day).start)
        assertEquals(LocalDate(2026, 1, 1), Period.of(PeriodKind.YEAR, day).start)
    }

    @Test
    fun aSundayBelongsToTheWeekThatStartedOnTheMondayBefore() {
        assertEquals(LocalDate(2026, 10, 26), Period.of(PeriodKind.WEEK, LocalDate(2026, 11, 1)).start)
    }

    @Test
    fun theEndIsTheLastDay() {
        assertEquals(LocalDate(2026, 11, 8), Period.of(PeriodKind.WEEK, day).end)
        assertEquals(LocalDate(2026, 11, 30), Period.of(PeriodKind.MONTH, day).end)
        assertEquals(LocalDate(2026, 12, 31), Period.of(PeriodKind.QUARTER, day).end)
        assertEquals(LocalDate(2026, 12, 31), Period.of(PeriodKind.YEAR, day).end)
        assertEquals(LocalDate(2028, 2, 29), Period.of(PeriodKind.MONTH, LocalDate(2028, 2, 3)).end)
    }

    @Test
    fun previousAndNextStepOneOfTheSameKind() {
        val quarter = Period.of(PeriodKind.QUARTER, day)
        assertEquals(LocalDate(2027, 1, 1), quarter.next().start)
        assertEquals(LocalDate(2026, 7, 1), quarter.previous().start)
        assertEquals(LocalDate(2026, 10, 26), Period.of(PeriodKind.WEEK, day).previous().start)
        assertEquals(LocalDate(2027, 1, 1), Period.of(PeriodKind.YEAR, day).next().start)
        assertEquals(LocalDate(2027, 11, 1), Period.of(PeriodKind.MONTH, day).nextYear().start)
    }

    @Test
    fun aPeriodIsOverTheDayAfterItsEnd() {
        val month = Period.of(PeriodKind.MONTH, day)
        assertFalse(month.isOver(LocalDate(2026, 11, 30)))
        assertTrue(month.isOver(LocalDate(2026, 12, 1)))
        assertFalse(Period.of(PeriodKind.DAY, day).isOver(day))
    }
}
