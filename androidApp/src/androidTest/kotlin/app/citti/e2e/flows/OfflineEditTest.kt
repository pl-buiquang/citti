package app.citti.e2e.flows

import app.citti.core.fake.Endpoint
import app.citti.e2e.E2e
import app.citti.e2e.ServerAssert
import app.citti.e2e.robots.ListRobot
import app.citti.e2e.robots.NoteRobot
import app.citti.e2e.robots.Strings
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

class OfflineEditTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    private val list = ListRobot(e2e)
    private val note = NoteRobot(e2e)
    private val server = ServerAssert()

    @Test
    fun editOfflineIsKeptPendingThenPushedOnce() {
        E2e.server.offline = true
        list.openNote("todo")
        note.typeAfter("Water the plants", "\nOffline line")
        note.assertSaveState(Strings.SAVE_OFFLINE)
        note.pressBack()
        list.assertRowShowsBadge("todo", Strings.BADGE_PENDING)
        assertTrue(E2e.server.requests.all { it.status == null }, "a request got through while offline")

        E2e.server.offline = false
        E2e.server.clearRequests()
        list.refresh()
        server.awaitRequest(Endpoint.UPDATE) { "Offline line" in it.body }
        server.assertRequests(Endpoint.UPDATE, 1)
        list.assertRow("todo")
        assertTrue("Offline line" in E2e.server.note(e2e.setup.id("todo"))!!.content)
    }

    @Test
    fun cachedImagesStillShowOffline() {
        list.openNote("images")
        note.showPreview()
        note.assertImageLoaded("by root")
        note.pressBack()

        E2e.server.offline = true
        list.openNote("images")
        note.showPreview()
        note.assertImageLoaded("by root")
    }
}
