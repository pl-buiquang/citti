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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * New note against a title the Folder already has (ADR 0006): the title-clash dialog when the check finds it, and
 * the push-time check when it could not run (offline). The server never gets a blind `Title (2)`.
 */
class NewNoteCollisionTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    private val list = ListRobot(e2e)
    private val note = NoteRobot(e2e)
    private val server = ServerAssert()

    /** Waits for the sync of the app's start, so the server changes that follow are not pulled by it. */
    private fun started() {
        list.assertRow("Ideas")
        E2e.server.clearRequests()
    }

    private fun later() {
        E2e.server.advance(60)
        E2e.clock.advance(1.minutes)
    }

    @Test
    fun newNoteOpensFocusedAndIsPushed() {
        started()
        list.tapNewNote()
        list.assertNewNoteFolder("")
        list.enterNewNoteTitle("Groceries")
        list.createNewNote()
        note.assertTitle("Groceries")
        note.assertEditorFocused()
        val created = server.awaitRequest(Endpoint.CREATE)
        assertTrue("\"title\":\"Groceries\"" in created.body, created.body)
        server.assertNote("Groceries", category = "", content = "")
    }

    @Test
    fun localClashOffersOpenExistingOrTheNextFreeTitle() {
        started()
        list.newNote("Ideas")
        list.assertTitleClash("Ideas (2)")
        list.openExistingNote()
        note.assertTitle("Ideas")
        note.leave()

        list.newNote("Ideas")
        list.createFreeTitle()
        note.assertTitle("Ideas (2)")
        note.assertEditorFocused()
        server.awaitRequest(Endpoint.CREATE) { "Ideas (2)" in it.body }
        server.assertNote("Ideas", category = "")
        server.assertNote("Ideas (2)", category = "")
    }

    @Test
    fun serverOnlyTitleIsFoundByTheFolderCheck() {
        started()
        later()
        E2e.server.addNote("From the web", "Written elsewhere.", category = "")
        list.assertNoRow("From the web")

        list.newNote("From the web")
        list.assertTitleClash("From the web (2)")
        server.awaitRequest(Endpoint.LIST) { it.query["category"] == "" }
        list.openExistingNote()
        note.assertTitle("From the web")
        note.assertText("Written elsewhere.")
        server.assertRequests(Endpoint.CREATE, 0)
    }

    @Test
    fun offlineUntouchedDuplicateIsDroppedAtPush() {
        started()
        E2e.server.offline = true
        list.newNote("Dup")
        note.assertTitle("Dup")
        note.leave()
        list.assertRowShowsBadge("Dup", Strings.BADGE_PENDING)

        // Meanwhile the same title is created elsewhere.
        later()
        E2e.server.addNote("Dup", "From the web", category = "")
        E2e.server.offline = false
        E2e.server.clearRequests()
        list.refresh()

        server.assertRequests(Endpoint.CREATE, 0)
        assertEquals(
            listOf("From the web"),
            E2e.server.notes
                .filter { it.title == "Dup" || it.title.startsWith("Dup (") }
                .map { it.content },
        )
        list.openNote("Dup")
        note.assertText("From the web")
    }

    @Test
    fun offlineEditedDuplicateBecomesAConflictCopy() {
        started()
        E2e.server.offline = true
        list.newNote("Dup")
        note.assertTitle("Dup")
        note.typeAtEnd("Typed offline")
        note.assertSaveState(Strings.SAVE_OFFLINE)
        note.leave()

        later()
        E2e.server.addNote("Dup", "From the web", category = "")
        E2e.server.offline = false
        E2e.server.clearRequests()
        list.refresh()

        val copyTitle = "Dup (conflict 2026-10-30)"
        server.awaitRequest(Endpoint.CREATE) { "Typed offline" in it.body }
        server.assertNote("Dup", category = "", content = "From the web")
        server.assertNote(copyTitle, category = "", content = "Typed offline")
        server.assertNoNote("Dup (2)")
        list.assertRowShowsBadge(copyTitle, Strings.BADGE_CONFLICT)
    }
}
