package app.citti.e2e.robots

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.performClick
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

    /** The header shows [label] ("October 2026"). */
    fun assertMonth(label: String) {
        waitFor(hasText(label) and isHeading())
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

    /** "Create daily note for [day]?" ([day] as "28 Oct"). */
    fun assertCreatePastDay(day: String) {
        waitFor(hasText(Strings.createPastDay(day)))
    }

    fun confirmCreate() = click(Strings.CREATE)
}
