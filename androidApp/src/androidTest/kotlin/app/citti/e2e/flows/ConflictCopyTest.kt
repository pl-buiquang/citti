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
import kotlin.time.Duration.Companion.minutes

class ConflictCopyTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    private val list = ListRobot(e2e)
    private val note = NoteRobot(e2e)
    private val server = ServerAssert()

    @Test
    fun serverWinsAndTheLocalTextBecomesAConflictCopy() {
        val id = e2e.setup.id("todo")
        list.openNote("todo")
        note.assertText("Pay rent")

        // Changed on the server while the Note is open; the push after the edit sends the old ETag.
        val serverText =
            E2e.server
                .note(id)!!
                .content
                .replace("Pay rent", "Pay rent (server)")
        E2e.server.advance(60)
        E2e.clock.advance(1.minutes)
        E2e.server.editOnServer(id, content = serverText)
        note.typeAfter("Water the plants", "\nLocal line")

        val created = server.awaitRequest(Endpoint.CREATE) { "Local line" in it.body }
        assertEquals(serverText, E2e.server.note(id)!!.content, "the server keeps its text")
        note.pressBack()
        val copy = E2e.server.notes.single { it.id != id && "Local line" in it.content }
        list.assertRowShowsBadge(copy.title, Strings.BADGE_CONFLICT)
        server.assertRequests(Endpoint.CREATE, 1)
        check(created.status == 200)
    }
}
