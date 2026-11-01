package app.citti.e2e.flows

import app.citti.e2e.AppIntent
import app.citti.e2e.E2e
import app.citti.e2e.WidgetFlow
import app.citti.e2e.localId
import app.citti.e2e.robots.ListRobot
import app.citti.e2e.robots.NoteRobot
import app.citti.e2e.robots.WidgetRobot
import app.citti.e2e.robots.WidgetStrings
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import app.citti.e2e.viewLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

/** The Note widget on the launcher of the `api36Google` device: the Note picker, the Reading position and the tap. */
@WidgetFlow
class NoteWidgetTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    private val intent = AppIntent(e2e)

    @After
    fun finishApp() = intent.finish()

    private val widget = WidgetRobot(WidgetStrings.NOTE_RECEIVER)
    private val list = ListRobot(e2e)
    private val note = NoteRobot(e2e)

    /** Places the widget on a clean launcher and picks [title] in the Note picker that "Add to home screen" opens. */
    private fun placeAndPick(title: String) {
        widget.place()
        widget.pickNote(title)
        widget.goHome()
    }

    @Ignore(
        "The Note picker that Add to home screen should open does not show within 10 s after the launcher reset on api36Google (it did show on a launcher that already held widgets, where its list was empty of the fixture notes). The picker has not been checked by hand yet.",
    )
    @Test
    fun thePickerChoosesTheNoteAndTheWidgetShowsItsText() {
        placeAndPick("todo")
        widget.waitForText("todo")
        widget.waitForText("- [ ] Buy milk")
    }

    @Ignore(
        "The Note picker that Add to home screen should open does not show within 10 s after the launcher reset on api36Google (it did show on a launcher that already held widgets, where its list was empty of the fixture notes). The picker has not been checked by hand yet.",
    )
    @Test
    fun theWidgetStartsWhereYouLeftTheNoteAndATapOpensItThere() {
        placeAndPick("long")
        widget.waitForText("long")
        // No Reading position yet: the widget shows the end of the Note.
        widget.waitForText("Line 2999: the quick brown fox jumps over the lazy dog.")

        list.openNote("long")
        note.assertEditMode()
        note.scrollEditorToLine(100)
        note.assertTopLine(100)
        // The screen reports the top line once scrolling has settled for 300 ms (a debounce, not a state to wait on).
        runBlocking { delay(SCROLL_SETTLE_MS) }
        note.leave()
        awaitStored(e2e.setup.localId("long"), 100)

        widget.goHome()
        widget.waitForText(LINE_100)
        widget.tapText(LINE_100)
        intent.restore()
        note.assertTitle("long")
        note.assertCaretLine(100)
        note.assertTopLine(100)
    }

    @Ignore(
        "The Note picker that Add to home screen should open does not show within 10 s after the launcher reset on api36Google (it did show on a launcher that already held widgets, where its list was empty of the fixture notes). The picker has not been checked by hand yet.",
    )
    @Test
    fun aNoteThatIsGoneShowsItAndTheTapOpensThePicker() {
        placeAndPick("todo")
        widget.waitForText("- [ ] Buy milk")
        E2e.server.deleteOnServer(e2e.setup.id("todo"))
        E2e.sync()
        widget.waitForText(WidgetStrings.NOTE_GONE)
        widget.tapText(WidgetStrings.NOTE_GONE)
        intent.restore()
        widget.pickNote("long")
        widget.goHome()
        widget.waitForText("long")
    }

    /** Waits until leaving the Note screen has written the Reading position [line] of [localId]. */
    private fun awaitStored(
        localId: Long,
        line: Long,
    ) = e2e.compose.waitUntil(STORE_TIMEOUT_MS) { viewLine(localId) == line }

    private companion object {
        const val SCROLL_SETTLE_MS = 700L
        const val STORE_TIMEOUT_MS = 5_000L
        const val LINE_100 = "Line 100: the quick brown fox jumps over the lazy dog."
    }
}
