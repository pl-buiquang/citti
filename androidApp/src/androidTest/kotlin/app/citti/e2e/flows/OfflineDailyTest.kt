package app.citti.e2e.flows

import app.citti.core.fake.Endpoint
import app.citti.e2e.E2e
import app.citti.e2e.ServerAssert
import app.citti.e2e.flows.DailyNotesTest.Companion.OCT_31
import app.citti.e2e.flows.DailyNotesTest.Companion.moveTo
import app.citti.e2e.robots.DailyRobot
import app.citti.e2e.robots.ListRobot
import app.citti.e2e.robots.NoteRobot
import app.citti.e2e.robots.Strings
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Today offline creates the Daily note on the phone; when the same day's note was made elsewhere meanwhile, the
 * untouched local copy is dropped at the next Sync (`created_from`, ADR 0006): never a `2026-10-31 (2)`. The open
 * note becomes the server's, with the Daily wording of the Dropped snackbar.
 */
class OfflineDailyTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    private val list = ListRobot(e2e)
    private val daily = DailyRobot(e2e)
    private val note = NoteRobot(e2e)
    private val server = ServerAssert()

    @Test
    fun offlineTodayIsDroppedForTheServersNote() {
        list.assertRow("Ideas")
        moveTo(OCT_31)
        E2e.server.clearRequests()
        E2e.server.offline = true

        daily.tapToday()
        note.assertTitle("2026-10-31")
        note.assertText("# samedi 31 octobre 2026")
        note.leave()
        list.assertRowShowsBadge("2026-10-31", Strings.BADGE_PENDING)
        assertTrue(E2e.server.requests.all { it.status == null }, "a request got through while offline")

        // Meanwhile today's note is made in Obsidian; the local one is open, untouched, when the phone reconnects.
        list.openNote("2026-10-31")
        note.assertText("samedi 31 octobre 2026")
        E2e.server.advance(60)
        E2e.server.addNote("2026-10-31", "# samedi 31 octobre 2026\n\nFrom Obsidian.", category = "journal")
        E2e.server.offline = false
        E2e.server.clearRequests()
        E2e.sync()

        daily.assertDroppedToday()
        note.assertText("From Obsidian.")
        server.assertRequests(Endpoint.CREATE, 0)
        server.assertNoNote("2026-10-31 (2)")
        assertEquals(
            listOf("# samedi 31 octobre 2026\n\nFrom Obsidian."),
            E2e.server.notes
                .filter { it.title.startsWith("2026-10-31") }
                .map { it.content },
        )
        note.leave()
        list.assertRow("2026-10-31")
    }
}
