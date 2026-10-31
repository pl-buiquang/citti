package app.citti.e2e.robots

import android.view.View
import android.widget.EditText
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.typeTextIntoFocusedView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasFocus
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withText
import app.citti.e2e.rules.E2eRules
import app.citti.ui.TestTags
import org.hamcrest.Matcher
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The Note screen, Edit and Preview modes. Edit mode is a platform `EditText` (ADR 0009), reached through
 * Espresso; Preview mode is Compose. Tests only see Note-level actions: editing, the ⋮ menu and its dialogs,
 * Insert image.
 */
class NoteRobot(
    e2e: E2eRules,
) : Robot(e2e) {
    private val preview get() = exists(hasTestTag(TestTags.NOTE_PREVIEW))

    fun assertTitle(title: String) {
        waitFor(hasText(title) and isHeading())
    }

    fun assertEditMode() {
        waitFor(hasTestTag(TestTags.NOTE_EDITOR))
    }

    fun assertPreviewMode() {
        waitFor(hasTestTag(TestTags.NOTE_PREVIEW))
    }

    fun showPreview() {
        waitFor(hasContentDescription(Strings.NOTE_PREVIEW) and hasClickAction()).performClick()
        assertPreviewMode()
    }

    fun showEdit() {
        waitFor(hasContentDescription(Strings.NOTE_EDIT) and hasClickAction()).performClick()
        assertEditMode()
    }

    /** The Note's text contains [text] (in either mode). */
    fun assertText(text: String) {
        if (preview) {
            scrollTo(TestTags.NOTE_PREVIEW, hasText(text, substring = true))
        } else {
            assertEditMode()
            waitForView { onView(isAssignableFrom(EditText::class.java)).check(matches(withText(containsString(text)))) }
        }
    }

    fun assertNoText(text: String) {
        assertEditMode()
        waitForView { onView(isAssignableFrom(EditText::class.java)).check(matches(withText(not(containsString(text))))) }
    }

    /** Puts the caret at the end of the first line containing [line], then types [text] (`\n` presses Enter). */
    fun typeAfter(
        line: String,
        text: String,
    ) {
        caretAfter(line)
        editText().perform(typeTextIntoFocusedView(text))
    }

    /** Focuses the editor with the caret at the end of the first line containing [line]. */
    fun caretAfter(line: String) {
        assertText(line)
        editText().perform(
            onEditText("caret after '$line'") { view ->
                val start = view.text.indexOf(line)
                view.setSelection(view.text.indexOf('\n', start).let { if (it < 0) view.text.length else it })
            },
        )
    }

    /** Types [text] at the end of the Note (an empty New note too). */
    fun typeAtEnd(text: String) {
        assertEditMode()
        editText().perform(
            onEditText("caret at the end") { view -> view.setSelection(view.text.length) },
            typeTextIntoFocusedView(text),
        )
    }

    /** Edit mode with the editor focused (a New note opens so, with the keyboard up). */
    fun assertEditorFocused() {
        assertEditMode()
        waitForView { editText().check(matches(hasFocus())) }
    }

    /** Selects the first occurrence of [word]. */
    fun select(word: String) {
        assertText(word)
        editText().perform(
            onEditText("select '$word'") { view ->
                val start = view.text.indexOf(word)
                view.setSelection(start, start + word.length)
            },
        )
    }

    /** A Format toolbar button (Bold, Italic, Heading, List, Checkbox, Link). */
    fun tapToolbar(label: String) {
        waitFor(hasContentDescription(label) and hasClickAction()).performClick()
    }

    fun undo() {
        waitFor(hasContentDescription(Strings.NOTE_UNDO) and hasClickAction()).performClick()
    }

    /** The save state under the title: "Saved", "Pending", "Offline (will sync)"… */
    fun assertSaveState(label: String) {
        waitFor(hasText(label))
    }

    /** Preview: toggles the task whose text is [label]. */
    fun tapTask(label: String) {
        // The toggleable row merges its text's description.
        scrollTo(
            TestTags.NOTE_PREVIEW,
            hasContentDescription(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox),
        ).performClick()
    }

    /** Preview: taps the link inside the paragraph containing [paragraph]. */
    fun tapLinkIn(paragraph: String) {
        val node = scrollTo(TestTags.NOTE_PREVIEW, hasText(paragraph, substring = true))
        node.onChildren().filterToOne(hasClickAction()).performClick()
    }

    fun assertImageLoaded(alt: String) {
        scrollTo(TestTags.NOTE_PREVIEW, hasContentDescription(alt) and hasClickAction())
    }

    fun assertImageMissing(alt: String) {
        scrollTo(TestTags.NOTE_PREVIEW, hasContentDescription("$alt, ${Strings.IMAGE_MISSING}"))
    }

    // ---- the ⋮ menu ----

    /** The top bar's ← (a system Back would first close the keyboard a New note opens with). */
    fun leave() {
        waitFor(hasContentDescription(Strings.BACK) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    // The menu's dismiss layer is clickable and merges the menu: its tag and labels are in the unmerged tree only.
    private val inMenu = hasAnyAncestor(hasTestTag(TestTags.NOTE_MENU))

    fun openMenu() {
        waitFor(hasTestTag(TestTags.NOTE_MENU_BUTTON)).performClick()
        waitFor(hasTestTag(TestTags.NOTE_MENU), unmerged = true)
    }

    /** The open menu shows exactly [labels], in this order. */
    fun assertMenuItems(vararg labels: String) {
        waitFor(hasTestTag(TestTags.NOTE_MENU), unmerged = true)
        val shown =
            compose
                .onAllNodes(inMenu and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .mapNotNull {
                    it.config
                        .getOrNull(SemanticsProperties.Text)
                        ?.firstOrNull()
                        ?.text
                }
        assertEquals(labels.toList(), shown, "menu items")
    }

    fun tapMenuItem(label: String) {
        waitFor(hasText(label) and inMenu, unmerged = true).performClick()
        waitGone(hasTestTag(TestTags.NOTE_MENU), unmerged = true)
    }

    /** ⋮ › Add to favorites (or Remove from favorites when [on] is false). */
    fun setFavorite(on: Boolean) {
        openMenu()
        tapMenuItem(if (on) Strings.MENU_FAVORITE_ADD else Strings.MENU_FAVORITE_REMOVE)
    }

    /** ⋮ › Rename…, [title], Rename. The dialog closes unless the title is refused (see [assertRenameRefused]). */
    fun rename(title: String) {
        openMenu()
        tapMenuItem(Strings.MENU_RENAME)
        waitFor(hasSetTextAction() and hasAnyAncestor(hasTestTag(TestTags.RENAME_FIELD)), unmerged = true).performTextReplacement(title)
        waitFor(hasTestTag(TestTags.RENAME_CONFIRM)).performClick()
    }

    fun assertRenameDone() {
        waitGone(hasTestTag(TestTags.RENAME_FIELD), unmerged = true)
    }

    /** The Rename dialog stays open with [error] under the field. */
    fun assertRenameRefused(error: String) {
        waitFor(hasText(error))
        waitFor(hasTestTag(TestTags.RENAME_FIELD), unmerged = true)
    }

    /** The Cancel of the open dialog. */
    fun cancelDialog() {
        click(Strings.CANCEL)
    }

    /** ⋮ › Move…: the Folder picker, opened at the Note's Folder. */
    fun openMove(): FolderPickerRobot {
        openMenu()
        tapMenuItem(Strings.MENU_MOVE)
        return FolderPickerRobot(e2e).also { it.assertShown() }
    }

    /** ⋮ › Delete, confirmed: the screen pops. */
    fun delete() {
        openMenu()
        tapMenuItem(Strings.MENU_DELETE)
        waitFor(hasTestTag(TestTags.DELETE_CONFIRM)).performClick()
    }

    fun openInfo() {
        openMenu()
        tapMenuItem(Strings.MENU_NOTE_INFO)
        waitFor(hasTestTag(TestTags.NOTE_INFO))
    }

    /** The Note info sheet shows [value] next to [key]. */
    fun assertInfo(
        key: String,
        value: String,
    ) {
        waitUntil("Note info $key = $value") {
            infoTexts().let { texts ->
                texts.indexOf(key).let {
                    it >= 0 &&
                        texts.getOrNull(it + 1) == value
                }
            }
        }
    }

    private fun infoTexts(): List<String> =
        compose
            .onAllNodes(hasTestTag(TestTags.NOTE_INFO))
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .map { it.text }

    fun assertNoInfoText(text: String) {
        waitFor(hasTestTag(TestTags.NOTE_INFO))
        assertFalse(text in infoTexts(), "Note info shows $text: ${infoTexts()}")
    }

    // ---- Insert image ----

    /** ⋮ › Insert image: the Camera / Gallery sheet. */
    fun insertImageFromMenu() {
        openMenu()
        tapMenuItem(Strings.MENU_INSERT_IMAGE)
        waitFor(hasTestTag(TestTags.IMAGE_SHEET))
    }

    /** The seventh toolbar button (the editor must be focused): the Camera / Gallery sheet. */
    fun insertImageFromToolbar() {
        waitFor(hasTestTag(TestTags.TOOLBAR_IMAGE)).performClick()
        waitFor(hasTestTag(TestTags.IMAGE_SHEET))
    }

    /** A row of the sheet; the test stubs the system picker it opens (Espresso-Intents). */
    fun pickGallery() {
        waitFor(hasTestTag(TestTags.IMAGE_GALLERY)).performClick()
    }

    fun pickCamera() {
        waitFor(hasTestTag(TestTags.IMAGE_CAMERA)).performClick()
    }

    /** The upload chip is gone: the upload ended. */
    fun assertNoUpload() {
        waitGone(hasTestTag(TestTags.UPLOAD_CHIP))
    }

    /** The editor's whole text. */
    fun editorText(): String {
        assertEditMode()
        var text = ""
        editText().perform(onEditText("read") { text = it.text.toString() })
        return text
    }

    private fun editText() = onView(isAssignableFrom(EditText::class.java))

    private fun onEditText(
        what: String,
        block: (EditText) -> Unit,
    ) = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isAssignableFrom(EditText::class.java)

        override fun getDescription() = what

        override fun perform(
            uiController: UiController,
            view: View,
        ) {
            val edit = view as EditText
            edit.requestFocus()
            block(edit)
            uiController.loopMainThreadUntilIdle()
        }
    }

    /** Retries a view assertion until it holds: the editor fills in from the database asynchronously. */
    private fun waitForView(check: () -> Unit) {
        waitUntil("the editor text") { runCatching(check).isSuccess }
        check()
    }
}
