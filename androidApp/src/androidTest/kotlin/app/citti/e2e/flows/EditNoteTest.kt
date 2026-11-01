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
import kotlin.test.assertEquals

class EditNoteTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    private val list = ListRobot(e2e)
    private val note = NoteRobot(e2e)
    private val server = ServerAssert()

    private fun todo() = E2e.server.note(e2e.setup.id("todo"))!!

    @Test
    fun enterContinuesTheListAndOnePushSendsTheExactText() {
        val before = todo().content
        list.openNote("todo")
        note.typeAfter("Water the plants", "\nCall mom")

        val expected = before.replace("- [ ] Water the plants\n", "- [ ] Water the plants\n- [ ] Call mom\n")
        server.awaitRequest(Endpoint.UPDATE) { "Call mom" in it.body }
        server.assertNote("todo", "", content = expected)
        server.assertRequests(Endpoint.UPDATE, 1)
    }

    @Test
    fun boldFromTheToolbarThenUndo() {
        val before = todo().content
        list.openNote("todo")
        note.select("milk")
        note.tapToolbar(Strings.TOOLBAR_BOLD)
        note.assertText("Buy **milk**")
        server.awaitRequest(Endpoint.UPDATE) { "**milk**" in it.body }
        assertEquals(before.replace("Buy milk", "Buy **milk**"), todo().content)

        note.undo()
        note.assertNoText("**milk**")
        server.awaitRequest(Endpoint.UPDATE) { "**milk**" !in it.body }
        assertEquals(before, todo().content)
    }

    @Test
    fun longNoteOpens() {
        list.openNote("long")
        note.assertTitle("long")
        // Opens in Preview; the whole text is in Edit mode.
        note.ensureEdit()
        note.assertText("The last line of the long Note.")
    }
}
