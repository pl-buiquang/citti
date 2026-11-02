package app.citti.ui.daily

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

/**
 * The Calendar's labels in the phone's locale (the app provides them with `java.time`, like [app.citti.ui.browse.RowDateFormatter]).
 * Daily note titles and Templates never use this: they go through `MomentFormat` and the Date locale.
 */
interface PhoneCalendarLabels {
    /** The grid's title: "October 2026". */
    fun monthYear(month: YearMonth): String

    /** The month alone, for the empty state: "October". */
    fun monthName(month: YearMonth): String

    /** The month alone as a title, the header's Month note target: "October". */
    fun monthTitle(month: YearMonth): String = monthName(month).replaceFirstChar { it.uppercase() }

    /** The year alone, the header's Year note target: "2026". */
    fun year(month: YearMonth): String = month.year.toString()

    /** True when the phone's locale writes the month before the year ("October 2026"), false for "2026年10月". */
    val monthFirst: Boolean get() = true

    /** A grid column header: "M". */
    fun weekdayInitial(day: DayOfWeek): String

    /** The column header read by TalkBack: "Monday". */
    fun weekdayName(day: DayOfWeek): String

    /** The first day of the week in the phone's locale, used when Week starts on is Automatic. */
    fun defaultWeekStart(): DayOfWeek

    /** A Daily note row under the grid: "Sat 31 Oct". */
    fun shortDate(date: LocalDate): String

    /** The day in a sentence ("Create daily note for 12 Oct?"): "12 Oct". */
    fun dayMonth(date: LocalDate): String

    /** A day cell read by TalkBack: "31 October". */
    fun dayMonthLong(date: LocalDate): String
}
