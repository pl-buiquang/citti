package app.citti.e2e.robots

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.citti.e2e.rules.E2eRules
import app.citti.ui.TestTags
import kotlin.test.assertFalse

/**
 * The full-screen Folder picker of New note (Change) and Move: Folders only, "New folder…", and the confirm button.
 *
 * The picker's root swallows taps (it is clickable) and so merges its list: the `folderPicker.rows` tag and the row
 * labels are in the unmerged tree only, where a tap on a label lands on its row.
 */
class FolderPickerRobot(
    e2e: E2eRules,
) : Robot(e2e) {
    private val inList = hasAnyAncestor(hasTestTag(TestTags.FOLDER_PICKER))

    fun assertShown() {
        waitFor(hasTestTag(TestTags.FOLDER_PICKER), unmerged = true)
    }

    fun openFolder(name: String) {
        waitFor(hasText(name) and inList, unmerged = true).performClick()
    }

    fun assertFolder(name: String) {
        waitFor(hasText(name) and inList, unmerged = true)
    }

    /** The fixture's Folders fit on one screen, so a missing row is not scrolled for. */
    fun assertNoFolder(name: String) {
        assertShown()
        assertFalse(exists(hasText(name) and inList, unmerged = true), "Folder $name is in the picker")
    }

    /** "New folder…", then [path] and Create: the picker moves into it. */
    fun newFolder(path: String) {
        waitFor(hasText(Strings.NEW_FOLDER) and inList, unmerged = true).performClick()
        waitFor(hasSetTextAction() and hasAnyAncestor(hasTestTag(TestTags.FOLDER_PICKER_NEW_FOLDER)), unmerged = true)
            .performTextReplacement(path)
        waitFor(hasText(Strings.CREATE, ignoreCase = true) and inList, unmerged = true).performClick()
    }

    /** An error above the confirm button or under the New folder field. */
    fun assertError(text: String) {
        waitFor(hasText(text))
    }

    /** Choose (New note) or Move here (Move). */
    fun confirm() {
        waitFor(hasTestTag(TestTags.FOLDER_PICKER_CONFIRM)).performClick()
    }
}
