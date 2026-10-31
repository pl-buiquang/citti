package app.citti.e2e.robots

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.citti.e2e.rules.E2eRules
import app.citti.ui.TestTags
import kotlin.test.assertFalse

class SearchRobot(
    e2e: E2eRules,
) : Robot(e2e) {
    private fun result(title: String) = hasText(title) and hasClickAction()

    fun search(query: String) {
        waitFor(hasContentDescription(Strings.SEARCH_HINT) and hasSetTextAction()).performTextReplacement(query)
    }

    fun assertResult(title: String) {
        scrollTo(TestTags.SEARCH_RESULTS, result(title))
    }

    fun assertNoResult(title: String) {
        assertFalse(listHas(TestTags.SEARCH_RESULTS, result(title)), "result $title is shown")
    }

    /** The "No notes match" message for [query]. */
    fun assertNothingFound(query: String) {
        waitFor(hasText("No notes match “$query”"))
    }

    fun openResult(title: String) {
        scrollTo(TestTags.SEARCH_RESULTS, result(title)).performClick()
    }
}
