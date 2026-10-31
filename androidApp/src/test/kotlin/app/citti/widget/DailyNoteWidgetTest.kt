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
class DailyNoteWidgetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val date = "Saturday 31 October"

    @Test
    fun foundShowsTheDateAndTheWindowAndEveryTapResumes() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            val content = (0 until 300).joinToString("\n") { "row%03d".format(it) }
            provideComposable { DailyNoteContent(DailyNoteState.Ready(date, NoteWindows.slice(content, viewLine = 10))) }

            onNode(hasText(date)).assertExists()
            onNode(hasText("row009")).assertDoesNotExist()
            onNode(hasText("row010")).assertExists()
            onNode(hasText(context.getString(R.string.widget_note_more))).assertExists()
            // The header, the 200 lines of the window and the "more" row.
            onAllNodes(hasStartActivityClickAction(WidgetIntents.daily(context, resume = true))).assertCountEquals(202)
            onAllNodes(hasStartActivityClickAction(WidgetIntents.daily(context, resume = false))).assertCountEquals(0)
        }

    @Test
    fun missingOffersToCreate() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { DailyNoteContent(DailyNoteState.Missing(date)) }

            onNode(hasText(date)).assertExists()
            onNode(hasText(context.getString(R.string.widget_daily_missing))).assertExists()
            onAllNodes(hasStartActivityClickAction(WidgetIntents.daily(context, resume = true))).assertCountEquals(2)
        }

    @Test
    fun downloadingShowsItsStateAndResumes() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { DailyNoteContent(DailyNoteState.Downloading(date)) }

            onNode(hasText(context.getString(R.string.widget_note_downloading))).assertExists()
            onAllNodes(hasStartActivityClickAction(WidgetIntents.daily(context, resume = true))).assertCountEquals(2)
        }

    @Test
    fun theGuardTellsTheJournalFolderIsUnsyncedAndTheTapGoesToTheAppLink() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { DailyNoteContent(DailyNoteState.Guard(date)) }

            onNode(hasText(context.getString(R.string.widget_daily_guard))).assertExists()
            onAllNodes(hasStartActivityClickAction(WidgetIntents.daily(context, resume = true))).assertCountEquals(2)
        }

    @Test
    fun signedOutOpensTheAppAndHasNoDailyLink() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { DailyNoteContent(DailyNoteState.SignedOut) }

            onNode(hasText(context.getString(R.string.widget_signed_out))).assertExists()
            onNode(hasStartActivityClickAction(WidgetIntents.app(context))).assertExists()
            onNode(hasStartActivityClickAction(WidgetIntents.daily(context, resume = true))).assertDoesNotExist()
        }
}
