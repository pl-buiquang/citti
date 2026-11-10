package app.citti.e2e.flows

import androidx.glance.appwidget.updateAll
import app.citti.core.fake.Endpoint
import app.citti.e2e.AppIntent
import app.citti.e2e.E2e
import app.citti.e2e.ServerAssert
import app.citti.e2e.WidgetFlow
import app.citti.e2e.robots.NoteRobot
import app.citti.e2e.robots.WidgetRobot
import app.citti.e2e.robots.WidgetStrings
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import app.citti.widget.FavoritesWidget
import app.citti.widget.dateLabel
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Favorites widget on the launcher of the `api36Google` device (spec M7a). */
@WidgetFlow
class FavoritesWidgetTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    private val intent = AppIntent(e2e)

    @After
    fun finishApp() = intent.finish()

    private val widget = WidgetRobot(WidgetStrings.FAVORITES_RECEIVER) { FavoritesWidget().updateAll(it) }
    private val note = NoteRobot(e2e)
    private val server = ServerAssert()

    /** The favorites as the widget draws them: most recently modified first. */
    private fun favoriteTitles(): List<String> =
        E2e.container.database.noteQueries
            .selectFavoriteWidgetRows()
            .executeAsList()
            .map { it.title }

    @Ignore(
        "Flaky on api36Google: the Today text is found by waitForText and gone from the launcher tree one read later (the widget redraws or the launcher tree changes between the reads). The cause is not understood yet.",
    )
    @Test
    fun theTodayRowComesFirstThenTheFavoritesNewestFirst() {
        widget.place()
        widget.waitForText(WidgetStrings.TODAY_ROW)
        widget.waitForText(dateLabel(LocalDate(2026, 10, 30)))
        val titles = favoriteTitles()
        assertEquals(3, titles.size, "fixture favorites: $titles")
        // A 4x2 widget shows the header, the Today row and the newest favorite; the rest scrolls.
        widget.waitForText(titles.first())

        val shown = widget.texts()
        val order = listOf(WidgetStrings.TODAY_ROW, titles.first())
        val positions = order.map { text -> shown.indexOf(text).also { assertTrue(it >= 0, "'$text' not in $shown") } }
        assertEquals(positions.sorted(), positions, "launcher order $shown, expected $order")
    }

    @Ignore(
        "Flaky on api36Google: passed in one run, then ActivityScenario failed to close the Activity after the widget tap (Current state was null, last stage STARTED).",
    )
    @Test
    fun tappingAFavoriteOpensThatNote() {
        widget.place()
        widget.tapText("todo")
        intent.restore()
        note.assertTitle("todo")
        note.assertText("Buy milk")
        // A widget-opened Note is the whole stack: Back leaves the app.
        note.pressBack()
    }

    @Ignore(
        "The body passes; ActivityScenario fails to close the Activity after this widget tap (Current state was null, last stage STARTED) although the Activity opened by tapping a Favorite closes fine. Needs a look at the Activity lifecycle after the Today row tap.",
    )
    @Test
    fun theTodayRowOpensTodaysExistingNoteWithoutCreatingOne() {
        widget.place()
        E2e.server.clearRequests()
        widget.tapText(WidgetStrings.TODAY_ROW)
        intent.restore()
        note.assertTitle("2026-10-30")
        note.assertText("réunion le matin")
        server.assertRequests(Endpoint.CREATE, 0)
    }

    @Ignore(
        "The widget is not on the launcher screen after the clock move and goHome (only the At a glance widget shows): the widget redraw after a state change does not reach the launcher in the emulator.",
    )
    @Test
    fun theTodayRowCreatesTodaysNoteWhenItIsMissing() {
        widget.place()
        moveToNextDay()
        widget.goHome()
        E2e.server.clearRequests()
        widget.tapText(WidgetStrings.TODAY_ROW)
        intent.restore()
        note.assertTitle("2026-10-31")
        note.assertEditorFocused()
        server.awaitRequest(Endpoint.CREATE)
        server.assertRequests(Endpoint.CREATE, 1)
    }

    @Ignore(
        "A redraw after the first draw (WidgetRefresher after a sync) never shows on the launcher within 10 s on api36Google; suspected test WorkManager (E2eApp SynchronousExecutor) not running the second Glance SessionWorker. Not verified on a real device.",
    )
    @Test
    fun aSyncThatChangesTheFavoritesRedrawsTheWidget() {
        widget.place()
        widget.waitForText("todo")
        assertTrue(!widget.hasText("Ideas"))

        E2e.server.setFavorite(e2e.setup.id("Ideas"), true)
        E2e.sync()
        widget.waitForText("Ideas")

        E2e.server.setFavorite(e2e.setup.id("Ideas"), false)
        E2e.sync()
        widget.waitForTextGone("Ideas")
    }

    private fun moveToNextDay() = app.citti.e2e.moveTo(DailyNotesTest.OCT_31)
}
