package app.citti.e2e.flows

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.citti.core.fake.Endpoint
import app.citti.e2e.E2e
import app.citti.e2e.ServerAssert
import app.citti.e2e.robots.ListRobot
import app.citti.e2e.robots.NoteRobot
import app.citti.e2e.robots.Strings
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

/**
 * What the user sees after Android destroys the app while it is in the background: the open Note, the typed text
 * and the unsent edit all come back, and the edit is pushed once the server is reachable.
 *
 * The test runs inside the app's process, so `am kill` would kill the test with it. Instead "Don't keep
 * activities" destroys the Activity as soon as the app leaves the screen, and the Activity comes back from its
 * saved state, as after a process kill (the in-memory state of the process is not what restores the screen).
 */
class ProcessDeathTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    private val list = ListRobot(e2e)
    private val note = NoteRobot(e2e)
    private val server = ServerAssert()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @After
    fun keepActivitiesAgain() {
        device.executeShellCommand("settings put global always_finish_activities 0")
    }

    @Test
    fun theOpenNoteAndTheUnsentEditComeBackAndArePushed() {
        E2e.server.offline = true
        list.openNote("todo")
        note.typeAfter("Water the plants", "\nWritten before the kill")
        note.assertSaveState(Strings.SAVE_OFFLINE)

        device.executeShellCommand("settings put global always_finish_activities 1")
        device.pressHome()
        device.executeShellCommand("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n app.citti/.MainActivity")
        e2e.compose.waitForIdle()

        note.assertTitle("todo")
        note.assertText("Written before the kill")

        device.executeShellCommand("settings put global always_finish_activities 0")
        E2e.server.offline = false
        E2e.server.clearRequests()
        note.pressBack()
        list.refresh()
        server.awaitRequest(Endpoint.UPDATE) { "Written before the kill" in it.body }
        assertTrue("Written before the kill" in E2e.server.note(e2e.setup.id("todo"))!!.content)
    }
}
