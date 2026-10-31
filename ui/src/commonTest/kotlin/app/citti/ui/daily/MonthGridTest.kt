package app.citti.ui.daily

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MonthGridTest {
    private val october = YearMonth(2026, 10)

    @Test
    fun weekdaysStartOnTheWeekStart() {
        assertEquals(DayOfWeek.MONDAY, weekdays(DayOfWeek.MONDAY).first())
        assertEquals(DayOfWeek.SUNDAY, weekdays(DayOfWeek.MONDAY).last())
        assertEquals(
            listOf(
                DayOfWeek.SATURDAY,
                DayOfWeek.SUNDAY,
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY,
            ),
            weekdays(DayOfWeek.SATURDAY),
        )
    }

    @Test
    fun octoberFromMondayHasLeadingAndTrailingDays() {
        // 1 October 2026 is a Thursday, 31 October a Saturday.
        val weeks = monthWeeks(october, DayOfWeek.MONDAY)
        assertEquals(5, weeks.size)
        assertTrue(weeks.all { it.size == 7 })
        assertEquals(GridDay(LocalDate(2026, 9, 28), inMonth = false), weeks.first().first())
        assertEquals(GridDay(LocalDate(2026, 10, 1), inMonth = true), weeks.first()[3])
        assertEquals(GridDay(LocalDate(2026, 10, 31), inMonth = true), weeks.last()[5])
        assertEquals(GridDay(LocalDate(2026, 11, 1), inMonth = false), weeks.last().last())
        assertEquals(31, weeks.flatten().count { it.inMonth })
    }

    @Test
    fun weekStartMovesTheLeadingDays() {
        val fromSunday = monthWeeks(october, DayOfWeek.SUNDAY)
        assertEquals(LocalDate(2026, 9, 27), fromSunday.first().first().date)
        assertEquals(LocalDate(2026, 10, 31), fromSunday.last().last().date)

        val fromSaturday = monthWeeks(october, DayOfWeek.SATURDAY)
        assertEquals(LocalDate(2026, 9, 26), fromSaturday.first().first().date)
        // 31 October is a Saturday: it opens a sixth row.
        assertEquals(6, fromSaturday.size)
        assertEquals(GridDay(LocalDate(2026, 10, 31), inMonth = true), fromSaturday.last().first())
        assertEquals(MAX_WEEKS, fromSaturday.size)
    }

    @Test
    fun aMonthOnFullWeeksHasNoLeadingOrTrailingDay() {
        // February 2026 starts on a Sunday and has 28 days.
        val weeks = monthWeeks(YearMonth(2026, 2), DayOfWeek.SUNDAY)
        assertEquals(4, weeks.size)
        assertTrue(weeks.flatten().all { it.inMonth })
    }

    @Test
    fun aLeapFebruaryEndsOnThe29th() {
        val days = monthWeeks(YearMonth(2028, 2), DayOfWeek.MONDAY).flatten().filter { it.inMonth }
        assertEquals(LocalDate(2028, 2, 29), days.last().date)
    }

    @Test
    fun pagesSpanTenYearsEitherSide() {
        val pages = CalendarPages(october)
        assertEquals(241, pages.count)
        assertEquals(YearMonth(2016, 10), pages.monthAt(0))
        assertEquals(YearMonth(2036, 10), pages.monthAt(pages.count - 1))
        assertEquals(120, pages.pageOf(october))
        assertEquals(october, pages.monthAt(pages.pageOf(october)))
        assertEquals(YearMonth(2027, 1), pages.monthAt(pages.pageOf(october) + 3))
    }

    @Test
    fun aMonthOutsideTheRangeGoesToTheEdge() {
        val pages = CalendarPages(october)
        assertEquals(0, pages.pageOf(YearMonth(2000, 1)))
        assertEquals(pages.count - 1, pages.pageOf(YearMonth(2050, 1)))
    }
}
