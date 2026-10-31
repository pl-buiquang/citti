package app.citti.e2e.robots

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import app.citti.e2e.rules.E2eRules
import app.citti.ui.TestTags
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** List and Tree mode, the drawer, pull-to-refresh and New note. Rows are found by their title. */
class ListRobot(
    e2e: E2eRules,
) : Robot(e2e) {
    private fun row(title: String) = hasText(title) and hasClickAction()

    /** A Folder row: its name plus the Note count. */
    private fun folderRow(name: String) = hasText(name) and hasText("(", substring = true) and hasClickAction()

    fun assertShown() {
        waitFor(hasTestTag(TestTags.BROWSE_ROWS))
    }

    fun assertFirstSyncEmpty() {
        waitFor(hasText(Strings.FIRST_SYNC_EMPTY))
    }

    fun assertRow(title: String) {
        scrollTo(TestTags.BROWSE_ROWS, row(title))
    }

    fun assertNoRow(title: String) {
        assertShown()
        assertFalse(listHas(TestTags.BROWSE_ROWS, row(title)), "row $title is shown")
    }

    /** The first rows from the top, by title. */
    fun assertFirstRows(vararg titles: String) {
        val list = waitFor(hasTestTag(TestTags.BROWSE_ROWS))
        list.performScrollToIndex(0)
        val shown =
            list
                .onChildren()
                .fetchSemanticsNodes()
                .mapNotNull {
                    it.config
                        .getOrNull(SemanticsProperties.Text)
                        ?.firstOrNull()
                        ?.text
                }
        assertEquals(titles.toList(), shown.take(titles.size), "rows: $shown")
    }

    fun openNote(title: String) {
        scrollTo(TestTags.BROWSE_ROWS, row(title)).performClick()
    }

    /** Pull-to-refresh, through its accessibility action. */
    @OptIn(ExperimentalTestApi::class)
    fun refresh() {
        val pull =
            SemanticsMatcher("has the ${Strings.SYNC_NOW} action") { node ->
                node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == Strings.SYNC_NOW } == true
            }
        waitFor(pull).performCustomAccessibilityActionWithLabel(Strings.SYNC_NOW)
        compose.waitForIdle()
    }

    fun showTree() = click(Strings.MODE_TREE)

    fun showList() = click(Strings.MODE_LIST)

    fun assertTreeShown() {
        waitFor(hasText(Strings.MODE_TREE, ignoreCase = true) and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
    }

    fun assertListShown() {
        waitFor(hasText(Strings.MODE_LIST, ignoreCase = true) and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
    }

    fun openFolder(name: String) {
        scrollTo(TestTags.BROWSE_ROWS, folderRow(name)).performClick()
    }

    fun assertFolder(name: String) {
        scrollTo(TestTags.BROWSE_ROWS, folderRow(name))
    }

    fun assertNoFolder(name: String) {
        assertShown()
        assertFalse(listHas(TestTags.BROWSE_ROWS, folderRow(name)), "Folder $name is listed")
    }

    /** The Breadcrumb ends on [name] (the current Folder is its heading; the heading holds the text node). */
    fun assertBreadcrumbAt(name: String) {
        waitFor(hasText(name) and hasParent(isHeading()), unmerged = true)
    }

    fun openBreadcrumb(name: String) = click(name)

    fun assertBanner(text: String) {
        waitFor(hasText(text, substring = true))
    }

    fun tapBannerAction(label: String) = click(label)

    fun openSearch(): SearchRobot {
        waitFor(hasContentDescription(Strings.SEARCH_HINT) and hasClickAction()).performClick()
        return SearchRobot(e2e)
    }

    fun openSettings(): SettingsRobot {
        waitFor(hasContentDescription(Strings.OPEN_MENU) and hasClickAction()).performClick()
        click(Strings.SETTINGS)
        return SettingsRobot(e2e).also { it.assertShown() }
    }

    fun assertRowShowsBadge(
        title: String,
        badge: String,
    ) {
        val node = scrollTo(TestTags.BROWSE_ROWS, row(title)).fetchSemanticsNode()
        val texts =
            node.config
                .getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .map { it.text }
        // Badges show in capitals.
        assertTrue(texts.any { it.equals(badge, ignoreCase = true) }, "row $title: $texts")
    }

    // ---- New note ----

    /** Opens the New note dialog: its Folder is the current Tree Folder, or the Vault root in List mode. */
    fun tapNewNote() {
        waitFor(hasTestTag(TestTags.NEW_NOTE_BUTTON)).performClick()
        // The dialog's card merges its fields: their tags are in the unmerged tree only.
        waitFor(hasTestTag(TestTags.NEW_NOTE_TITLE), unmerged = true)
    }

    fun enterNewNoteTitle(title: String) {
        waitFor(hasSetTextAction() and hasAnyAncestor(hasTestTag(TestTags.NEW_NOTE_TITLE)), unmerged = true).performTextReplacement(title)
    }

    /** The Folder line of the dialog: "Folder: work" ("Notes" for the Vault root). */
    fun assertNewNoteFolder(folder: String) {
        waitFor(hasText(Strings.newNoteFolder(folder)))
    }

    /** The dialog's Change: the Folder picker. */
    fun changeNewNoteFolder(): FolderPickerRobot {
        waitFor(hasTestTag(TestTags.NEW_NOTE_CHANGE_FOLDER)).performClick()
        return FolderPickerRobot(e2e).also { it.assertShown() }
    }

    /** Create: the server check runs, then the Note opens, or the title-clash dialog shows. */
    fun createNewNote() {
        waitFor(hasTestTag(TestTags.NEW_NOTE_CREATE)).performClick()
    }

    /** New note, [title], Create. */
    fun newNote(title: String) {
        tapNewNote()
        enterNewNoteTitle(title)
        createNewNote()
    }

    /** "A note with this title exists", offering Create "[freeTitle]". */
    fun assertTitleClash(freeTitle: String) {
        waitFor(hasTestTag(TestTags.TITLE_CLASH_OPEN))
        waitFor(hasTestTag(TestTags.TITLE_CLASH_CREATE) and hasText(Strings.titleClashCreate(freeTitle), ignoreCase = true))
    }

    fun openExistingNote() {
        waitFor(hasTestTag(TestTags.TITLE_CLASH_OPEN)).performClick()
    }

    fun createFreeTitle() {
        waitFor(hasTestTag(TestTags.TITLE_CLASH_CREATE)).performClick()
    }

    /** The Cancel of the New note or title-clash dialog. */
    fun cancelNewNote() {
        click(Strings.CANCEL)
        waitGone(hasTestTag(TestTags.TITLE_CLASH_OPEN) or hasTestTag(TestTags.NEW_NOTE_TITLE), unmerged = true)
    }
}
