package app.citti.e2e.robots

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import app.citti.e2e.rules.E2eRules

/**
 * Daily notes outside the Calendar: the Today button of List, Tree and Calendar mode, and what a Daily note shows
 * (the Template missing banner, the Dropped snackbar). The Daily note itself is a Note: [NoteRobot] reads it.
 */
class DailyRobot(
    e2e: E2eRules,
) : Robot(e2e) {
    /** The Today button ("Today's note"): today's Daily note opens, or is created from the Template. */
    fun tapToday() {
        waitFor(hasContentDescription(Strings.TODAY_NOTE) and hasClickAction()).performClick()
    }

    /** A new Daily note whose Template was found nowhere. */
    fun assertTemplateMissing(path: String) {
        waitFor(hasText(Strings.templateMissing(path), substring = true))
    }

    /** The untouched local copy of today's Daily note was dropped for the server's. */
    fun assertDroppedToday() = assertSnackbar(Strings.DROPPED_TODAY)
}
