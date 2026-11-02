package app.citti.e2e.robots

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.citti.e2e.rules.E2eRules
import app.citti.ui.TestTags

/**
 * Calendar mode: the month header, the grid and the past day dialog. Days are named as TalkBack reads them, in the
 * phone's locale ("30 October"); a day with a Daily note reads "30 October, daily note".
 */
class CalendarRobot(
    e2e: E2eRules,
) : Robot(e2e) {
    private fun day(label: String) =
        (hasContentDescription(Strings.calendarDayNote(label)) or hasContentDescription(Strings.calendarDayNone(label))) and
            hasClickAction()

    fun assertShown() {
        waitFor(hasTestTag(TestTags.CALENDAR_GRID))
    }

    /** The header shows [label] ("October 2026"): the month and the year are two buttons. */
    fun assertMonth(label: String) {
        waitFor(hasTestTag(TestTags.CALENDAR_MONTH_NOTE) and hasText(label.substringBeforeLast(' ')))
        waitFor(hasTestTag(TestTags.CALENDAR_YEAR_NOTE) and hasText(label.substringAfterLast(' ')))
    }

    /** The month of the header: its Month note opens, or is created. */
    fun tapMonth() {
        waitFor(hasTestTag(TestTags.CALENDAR_MONTH_NOTE)).performClick()
    }

    /** The year of the header: its Year note opens, or is created. */
    fun tapYear() {
        waitFor(hasTestTag(TestTags.CALENDAR_YEAR_NOTE)).performClick()
    }

    /** The quarter chip of the header ([label] as "Q4"); it is off until Show quarter is on. */
    fun tapQuarter(label: String) {
        waitFor(hasTestTag(TestTags.CALENDAR_QUARTER_NOTE) and hasText(label)).performClick()
    }

    fun assertNoQuarterChip() {
        waitGone(hasTestTag(TestTags.CALENDAR_QUARTER_NOTE))
    }

    /** The week number of the row that starts on [firstDay] (an ISO date); it is off until Show week numbers is on. */
    fun tapWeek(firstDay: String) {
        waitFor(hasTestTag(TestTags.calendarWeek(firstDay))).performClick()
    }

    fun assertNoWeekNumbers(firstDay: String) {
        waitGone(hasTestTag(TestTags.calendarWeek(firstDay)))
    }

    /** A long press on the day [label] opens the note of its week. */
    fun longPressDay(label: String) {
        waitFor(day(label)).performTouchInput { longClick() }
    }

    fun previousMonth() {
        waitFor(hasContentDescription(Strings.CALENDAR_PREVIOUS_MONTH) and hasClickAction()).performClick()
    }

    fun nextMonth() {
        waitFor(hasContentDescription(Strings.CALENDAR_NEXT_MONTH) and hasClickAction()).performClick()
    }

    /** The day [label] has a Daily note (its dot). */
    fun assertNoteDay(label: String) {
        waitFor(hasContentDescription(Strings.calendarDayNote(label)))
    }

    fun assertEmptyDay(label: String) {
        waitFor(hasContentDescription(Strings.calendarDayNone(label)))
    }

    /** Taps the day [label]: its Daily note opens, or (a past day without one) the Create dialog shows. */
    fun tapDay(label: String) {
        waitFor(day(label)).performClick()
    }

    /** The Create dialog of a past period, titled [title] ("Create month note for September 2026?"). */
    fun assertCreatePast(title: String) {
        waitFor(hasText(title))
    }

    /** "Create daily note for [day]?" ([day] as "28 Oct"). */
    fun assertCreatePastDay(day: String) {
        waitFor(hasText(Strings.createPastDay(day)))
    }

    fun confirmCreate() = click(Strings.CREATE)
}
