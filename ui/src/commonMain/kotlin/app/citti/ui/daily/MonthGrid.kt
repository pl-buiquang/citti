package app.citti.ui.daily

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.monthsUntil
import kotlinx.datetime.plus
import kotlinx.datetime.yearMonth

/** A cell of the Calendar grid: a day of the month shown, or a leading or trailing day of the month next to it. */
data class GridDay(
    val date: LocalDate,
    val inMonth: Boolean,
)

/** The most weeks a month spans (a 31-day month starting on the last day of the week). */
const val MAX_WEEKS = 6

private const val DAYS_IN_WEEK = 7

/** The seven weekdays of a grid row, from [weekStart]. */
fun weekdays(weekStart: DayOfWeek): List<DayOfWeek> =
    (0 until DAYS_IN_WEEK).map { DayOfWeek((weekStart.isoDayNumber - 1 + it) % DAYS_IN_WEEK + 1) }

/**
 * The weeks of [month] as rows of seven days from [weekStart]: the first row starts with the leading days of the
 * month before, the last one ends with the trailing days of the month after. Only the weeks the month touches.
 */
fun monthWeeks(
    month: YearMonth,
    weekStart: DayOfWeek,
): List<List<GridDay>> {
    val first = month.firstDay
    val leading = (first.dayOfWeek.isoDayNumber - weekStart.isoDayNumber + DAYS_IN_WEEK) % DAYS_IN_WEEK
    val start = first.minus(leading, DateTimeUnit.DAY)
    val weeks = (leading + month.numberOfDays + DAYS_IN_WEEK - 1) / DAYS_IN_WEEK
    return (0 until weeks).map { week ->
        (0 until DAYS_IN_WEEK).map { day ->
            val date = start.plus(week * DAYS_IN_WEEK + day, DateTimeUnit.DAY)
            GridDay(date, inMonth = date.yearMonth == month)
        }
    }
}

/** The ISO 8601 week number of [date] (weeks start on Monday, week 1 holds the first Thursday of the year). */
fun isoWeekNumber(date: LocalDate): Int {
    val week = (date.dayOfYear - date.dayOfWeek.isoDayNumber + ISO_WEEK_OFFSET) / DAYS_IN_WEEK
    return when {
        week < 1 -> isoWeeksIn(date.year - 1)
        week > isoWeeksIn(date.year) -> 1
        else -> week
    }
}

private const val ISO_WEEK_OFFSET = 10

private const val LONG_ISO_YEAR_WEEKS = 53

private const val SHORT_ISO_YEAR_WEEKS = 52

/** 53 for the years that start on a Thursday, or leap years that start on a Wednesday; 52 for the others. */
private fun isoWeeksIn(year: Int): Int {
    fun p(y: Int) = (y + y / 4 - y / 100 + y / 400) % DAYS_IN_WEEK
    return if (p(year) == 4 || p(year - 1) == 3) LONG_ISO_YEAR_WEEKS else SHORT_ISO_YEAR_WEEKS
}

/** Years shown either side of the month the pager starts around. */
const val CALENDAR_YEARS = 10

private const val MONTHS_IN_YEAR = 12

/** The months of the Calendar pager: [CALENDAR_YEARS] years before and after [center], one page each. */
class CalendarPages(
    center: YearMonth,
) {
    private val first = center.minus(CALENDAR_YEARS * MONTHS_IN_YEAR, DateTimeUnit.MONTH)

    val count = 2 * CALENDAR_YEARS * MONTHS_IN_YEAR + 1

    fun monthAt(page: Int): YearMonth = first.plus(page, DateTimeUnit.MONTH)

    /** The page of [month], or the first or last page for a month outside the range. */
    fun pageOf(month: YearMonth): Int = first.monthsUntil(month).coerceIn(0, count - 1)
}
