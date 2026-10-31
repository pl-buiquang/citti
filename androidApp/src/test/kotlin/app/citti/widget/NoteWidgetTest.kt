package app.citti.widget

import android.content.Context
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.citti.R
import app.citti.core.note.NoteWindows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class NoteWidgetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun ready(
        content: String,
        viewLine: Int?,
    ) = NoteWidgetState.Ready(5, "Trip plan", "personal/trips", viewLine, NoteWindows.slice(content, viewLine))

    @Test
    fun readyShowsTheHeaderAndTheWindowFromTheReadingPosition() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { NoteContent(ready("zero\none\ntwo\nthree", viewLine = 1)) }

            onNode(hasText("Trip plan")).assertExists()
            onNode(hasText("personal/trips")).assertExists()
            onNode(hasText("zero")).assertDoesNotExist()
            onNode(hasText("one")).assertExists()
            onNode(hasText("three")).assertExists()
            onNode(hasText(context.getString(R.string.widget_note_more))).assertDoesNotExist()
        }

    @Test
    fun aLongNoteEndsWithOpenToReadMore() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            val content = (0 until 300).joinToString("\n") { "line$it" }
            provideComposable { NoteContent(ready(content, viewLine = 0)) }

            onNode(hasText(context.getString(R.string.widget_note_more))).assertExists()
        }

    @Test
    fun theHeaderTheBodyAndMoreOpenTheNoteAtTheReadingPosition() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { NoteContent(ready("a\nb\nc", viewLine = 1)) }

            // The header and the two lines of the window (b, c).
            onAllNodes(hasStartActivityClickAction(WidgetIntents.note(context, 5, line = 1))).assertCountEquals(3)
            onAllNodes(hasStartActivityClickAction(WidgetIntents.note(context, 5, line = null))).assertCountEquals(0)
        }

    @Test
    fun withoutAReadingPositionTheTapOpensTheNoteWithoutALine() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { NoteContent(ready("a\nb\nc", viewLine = null)) }

            // The header and the three lines.
            onAllNodes(hasStartActivityClickAction(WidgetIntents.note(context, 5, line = null))).assertCountEquals(4)
        }

    @Test
    fun aGoneNoteOffersThePickerOfThisWidget() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { NoteContent(NoteWidgetState.Unavailable(appWidgetId = 42)) }

            onNode(hasText(context.getString(R.string.widget_note_gone))).assertExists()
            onNode(hasStartActivityClickAction(WidgetIntents.picker(context, 42))).assertExists()
            onNode(hasStartActivityClickAction(WidgetIntents.picker(context, 43))).assertDoesNotExist()
        }

    @Test
    fun aNoteWithoutTextShowsDownloading() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { NoteContent(NoteWidgetState.Downloading(5, "Trip plan", "")) }

            onNode(hasText("Trip plan")).assertExists()
            onNode(hasText(context.getString(R.string.widget_note_downloading))).assertExists()
        }

    @Test
    fun signedOutAsksToSignInAndOpensTheApp() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { NoteContent(NoteWidgetState.SignedOut) }

            onNode(hasText(context.getString(R.string.widget_signed_out))).assertExists()
            onNode(hasStartActivityClickAction(WidgetIntents.app(context))).assertExists()
        }
}
