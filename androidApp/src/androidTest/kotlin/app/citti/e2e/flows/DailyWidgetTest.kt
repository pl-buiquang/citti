package app.citti.e2e.flows

import android.content.Intent
import androidx.glance.appwidget.updateAll
import app.citti.core.fake.Endpoint
import app.citti.e2e.AppIntent
import app.citti.e2e.E2e
import app.citti.e2e.ServerAssert
import app.citti.e2e.WidgetFlow
import app.citti.e2e.localId
import app.citti.e2e.moveTo
import app.citti.e2e.robots.NoteRobot
import app.citti.e2e.robots.WidgetRobot
import app.citti.e2e.robots.WidgetStrings
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import app.citti.e2e.setViewLine
import app.citti.widget.DailyNoteWidget
import app.citti.widget.DayChangeReceiver
import app.citti.widget.dateLabel
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The Daily note widget on the launcher of the `api36Google` device, with the clock pinned through `AppConfig`. */
@WidgetFlow
class DailyWidgetTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    private val intent = AppIntent(e2e)

    @After
    fun finishApp() = intent.finish()

    private val widget = WidgetRobot(WidgetStrings.DAILY_RECEIVER) { DailyNoteWidget().updateAll(it) }
    private val note = NoteRobot(e2e)
    private val server = ServerAssert()

    @Ignore(
        "The tap creates the note, but the redraw after the creation (updateDaily) never shows the new text on the launcher within 10 s on api36Google; a redraw after the first draw does not seem to reach the launcher in the emulator.",
    )
    @Test
    fun withoutANoteTodayTheWidgetSaysSoAndATapCreatesItFromTheTemplate() {
        moveTo(DailyNotesTest.OCT_31)
        widget.place()
        widget.waitForText(dateLabel(LocalDate(2026, 10, 31)))
        widget.waitForText(WidgetStrings.DAILY_MISSING)

        E2e.server.clearRequests()
        widget.tapText(WidgetStrings.DAILY_MISSING)
        intent.restore()
        note.assertTitle("2026-10-31")
        note.assertEditorFocused()
        note.assertText("# samedi 31 octobre 2026")
        server.awaitRequest(Endpoint.CREATE)
        server.assertRequests(Endpoint.CREATE, 1)

        // The note exists now: the widget shows its text instead of the invitation.
        widget.goHome()
        widget.waitForText("Week 44")
        assertTrue(!widget.hasText(WidgetStrings.DAILY_MISSING))
    }

    @Ignore(
        "Flaky on api36Google: passes alone, but in the full widget run ActivityScenario fails to close the Activity after the widget tap (Current state was null, last stage STARTED).",
    )
    @Test
    fun anExistingNoteShowsFromItsReadingPositionAndATapOpensItThere() {
        setViewLine(e2e.setup.localId("2026-10-30"), 2)
        widget.place()
        widget.waitForText(dateLabel(LocalDate(2026, 10, 30)))
        widget.waitForText(TODAY_BODY)
        assertTrue(!widget.hasText("# vendredi 30 octobre 2026"), "the widget starts at the Reading position")

        E2e.server.clearRequests()
        widget.tapText(TODAY_BODY)
        intent.restore()
        note.assertTitle("2026-10-30")
        note.assertCaretLine(2)
        server.assertRequests(Endpoint.CREATE, 0)
    }

    @Ignore(
        "The redraw triggered by DayChangeReceiver does not show the new date on the launcher within 10 s on api36Google; a redraw after the first draw does not seem to reach the launcher in the emulator.",
    )
    @Test
    fun afterMidnightTheWidgetShowsTheNewDayAndATapOpensIt() {
        widget.place()
        widget.waitForText(dateLabel(LocalDate(2026, 10, 30)))

        // 00:01 in Paris (UTC+1 since the clocks went back on 2026-10-25), before any alarm could redraw the widget.
        moveTo(Instant.parse("2026-10-30T23:01:00Z"))
        E2e.app.sendBroadcast(Intent(E2e.app, DayChangeReceiver::class.java))

        widget.waitForText(dateLabel(LocalDate(2026, 10, 31)))
        widget.waitForText(WidgetStrings.DAILY_MISSING)
        widget.tapText(WidgetStrings.DAILY_MISSING)
        intent.restore()
        note.assertTitle("2026-10-31")
    }

    private companion object {
        const val TODAY_BODY = "Aujourd'hui, réunion le matin et vélo l'après-midi."
    }
}
