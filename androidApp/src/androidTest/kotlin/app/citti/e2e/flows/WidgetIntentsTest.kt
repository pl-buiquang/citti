package app.citti.e2e.flows

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import app.citti.MainActivity
import app.citti.core.fake.Endpoint
import app.citti.e2e.E2e
import app.citti.e2e.ServerAssert
import app.citti.e2e.localId
import app.citti.e2e.moveTo
import app.citti.e2e.robots.ListRobot
import app.citti.e2e.robots.NoteRobot
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import app.citti.e2e.setViewLine
import app.citti.ui.nav.DeepLink
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.time.Instant

/**
 * The `citti://` links the widgets start (spec M7a): a Note at a line, today's Daily note, and the links that must
 * do nothing. Runs on the ATD image: a link is an `ACTION_VIEW` sent to the app, no launcher involved.
 */
class WidgetIntentsTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    private val list = ListRobot(e2e)
    private val note = NoteRobot(e2e)
    private val server = ServerAssert()

    /** The Activity under test: the rule's, or the one a link started. */
    private var scenario: ActivityScenario<MainActivity>? = null
    private val running get() = scenario ?: e2e.compose.activityRule.scenario

    @After
    fun closeStartedScenario() {
        scenario?.close()
    }

    private fun view(
        uri: String,
        context: android.content.Context = E2e.app,
    ) = Intent(Intent.ACTION_VIEW, Uri.parse(uri), context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * A link while the app is running: `singleTop` delivers it to `onNewIntent`, which calls `setIntent`. ActivityScenario
     * ignores the lifecycle events of an Activity whose intent is not the one it started, so the original intent is
     * put back once the link is applied (a harness detail: the app keeps the link's intent in real use).
     */
    private fun sendToRunningApp(uri: String) {
        var original: Intent? = null
        running.onActivity { original = it.intent }
        running.onActivity { it.startActivity(view(uri, it).apply { flags = 0 }) }
        e2e.compose.waitForIdle()
        running.onActivity { it.intent = original }
    }

    /** A link that starts the app: the running Activity is closed first. */
    private fun startAppWith(uri: String) {
        e2e.compose.activityRule.scenario
            .close()
        scenario = ActivityScenario.launch<MainActivity>(view(uri))
        e2e.compose.waitForIdle()
    }

    @Test
    fun aNoteLinkOpensTheNoteAtTheLineWhenTheAppStarts() {
        list.assertRow("Ideas")
        startAppWith(DeepLink.noteUri(e2e.setup.localId("long"), 100))
        note.assertTitle("long")
        // Opens in Preview, on the block that holds the line.
        note.assertPreviewShows("Line 100:")
        note.pressBack()
        list.assertRow("Ideas")
    }

    @Test
    fun aNoteLinkOpensTheNoteAtTheLineWhileTheAppRuns() {
        list.assertRow("Ideas")
        sendToRunningApp(DeepLink.noteUri(e2e.setup.localId("long"), 40))
        note.assertTitle("long")
        note.assertPreviewShows("Line 40:")
        // The link resets the stack to [root, Note]: Back lands on the root.
        note.pressBack()
        list.assertRow("Ideas")
    }

    @Test
    fun aNoteLinkWithoutALineKeepsTheNormalOpening() {
        list.assertRow("Ideas")
        sendToRunningApp(DeepLink.noteUri(e2e.setup.localId("todo"), null))
        note.assertTitle("todo")
        note.assertText("Buy milk")
    }

    @Test
    fun aMalformedLinkOpensTheAppNormally() {
        list.assertRow("Ideas")
        startAppWith("citti://note/abc")
        list.assertRow("Ideas")
        sendToRunningApp("citti://note/0")
        list.assertRow("Ideas")
        sendToRunningApp("citti://daily/2026-10-31")
        list.assertRow("Ideas")
    }

    @Test
    fun aDeletedNoteGoesToTheRootWithoutACrash() {
        list.assertRow("Ideas")
        sendToRunningApp(DeepLink.noteUri(987_654, 3))
        list.assertRow("Ideas")
    }

    @Test
    fun recreatingTheActivityDoesNotApplyTheLinkAgain() {
        list.assertRow("Ideas")
        // The link starts the app, so the Activity under recreation holds the link's own intent.
        startAppWith(DeepLink.noteUri(e2e.setup.localId("todo"), 2))
        note.assertTitle("todo")
        note.pressBack()
        list.assertRow("Ideas")
        e2e.compose.waitForIdle()
        checkNotNull(scenario).recreate()
        e2e.compose.waitForIdle()
        list.assertRow("Ideas")
    }

    @Test
    fun theDailyLinkOpensTodaysNote() {
        list.assertRow("Ideas")
        E2e.server.clearRequests()
        sendToRunningApp(DeepLink.dailyUri(resume = false))
        note.assertTitle("2026-10-30")
        note.assertText("réunion le matin")
        server.assertRequests(Endpoint.CREATE, 0)
    }

    @Test
    fun theDailyLinkCreatesTodaysNoteWhenMissing() {
        list.assertRow("Ideas")
        moveTo(OCT_31)
        E2e.server.clearRequests()
        sendToRunningApp(DeepLink.dailyUri(resume = true))
        note.assertTitle("2026-10-31")
        note.assertEditorFocused()
        server.awaitRequest(Endpoint.CREATE)
        server.assertRequests(Endpoint.CREATE, 1)
    }

    @Test
    fun theResumingDailyLinkOpensTheNoteAtItsReadingPosition() {
        list.assertRow("Ideas")
        setViewLine(e2e.setup.localId("2026-10-30"), 2)
        sendToRunningApp(DeepLink.dailyUri(resume = true))
        note.assertTitle("2026-10-30")
        note.assertCaretLine(2)
    }

    private companion object {
        val OCT_31: Instant = DailyNotesTest.OCT_31
    }
}
