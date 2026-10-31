package app.citti.e2e.robots

import android.view.View
import android.widget.EditText
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.typeTextIntoFocusedView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withText
import app.citti.e2e.rules.E2eRules
import app.citti.ui.TestTags
import org.hamcrest.Matcher
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not

/**
 * The Note screen, Edit and Preview modes. Edit mode is a platform `EditText` (ADR 0009), reached through
 * Espresso; Preview mode is Compose. Tests only see Note-level actions.
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
        assertText(line)
        editText().perform(
            onEditText("caret after '$line'") { view ->
                val start = view.text.indexOf(line)
                view.setSelection(view.text.indexOf('\n', start).let { if (it < 0) view.text.length else it })
            },
            typeTextIntoFocusedView(text),
        )
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
