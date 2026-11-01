package app.citti.e2e.flows

import app.citti.e2e.E2e
import app.citti.e2e.robots.ListRobot
import app.citti.e2e.robots.NoteRobot
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

class ServerChangesTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    private val list = ListRobot(e2e)
    private val note = NoteRobot(e2e)

    private fun later() {
        E2e.server.advance(60)
        E2e.clock.advance(1.minutes)
    }

    @Test
    fun serverEditUpdatesTheOpenNote() {
        list.openNote("todo")
        note.assertText("Buy milk")
        later()
        val todo = E2e.server.note(e2e.setup.id("todo"))!!
        E2e.server.editOnServer(todo.id, content = todo.content.replace("Buy milk", "Buy oat milk"))
        E2e.sync()
        note.assertText("Buy oat milk")
    }

    @Ignore(
        "Flaky, cause not established: rarely, the sync that deletes the open Note makes Compose throw " +
            "IllegalStateException \"LayoutNode should be attached to an owner\" from a Row placement during the " +
            "layout pass while the Note screen is popped. It looks like a race between the screen leaving the " +
            "composition and a layout pass of that same frame; it did not reproduce in 4 local runs, so it is not fixed.",
    )
    @Test
    fun serverDeleteOfTheOpenNoteGoesBackToTheList() {
        list.openNote("todo")
        note.assertTitle("todo")
        later()
        E2e.server.deleteOnServer(e2e.setup.id("todo"))
        E2e.sync()
        list.assertRow("Roadmap")
        list.assertNoRow("todo")
    }

    @Test
    fun newServerNoteShowsUp() {
        list.assertRow("Roadmap")
        later()
        E2e.server.addNote("From the web", "Written elsewhere.", category = "work")
        list.refresh()
        list.assertRow("From the web")
    }
}
