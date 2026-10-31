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
 * The Folder refresh behind New note: one conditional `LIST?category=<folder>` with the Folder's own ETag, never a
 * full list, and it deletes nothing outside that exact Folder.
 */
class FolderRefreshTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    private val list = ListRobot(e2e)
    private val note = NoteRobot(e2e)
    private val server = ServerAssert()

    @Test
    fun newNoteListsItsFolderOnceThenTheUnchangedFolderIs304() {
        list.showTree()
        list.openFolder("work")
        list.assertRow("Standup")
        // The sync of the app's start is done (the finders wait for it): only the requests from here on count.
        E2e.server.clearRequests()
        list.tapNewNote()
        list.assertNewNoteFolder("work")
        list.enterNewNoteTitle("Fresh")
        list.createNewNote()
        note.assertTitle("Fresh")
        server.awaitRequest(Endpoint.CREATE) { "Fresh" in it.body }

        // The creation check lists the Folder (200); the push-time check of the same Folder, right after, is a 304.
        val lists = server.requests(Endpoint.LIST)
        assertTrue(lists.all { it.query["category"] == "work" }, "lists: ${lists.map { it.query }}")
        assertEquals(listOf(200, 304), lists.map { it.status }, "LIST statuses")
        assertEquals("work", ServerAssert().note("Fresh")!!.category)
    }

    @Test
    fun refreshDeletesOnlyInsideTheFolder() {
        list.showTree()
        list.openFolder("work")
        list.assertRow("Standup")
        E2e.server.clearRequests()
        E2e.server.advance(60)
        E2e.clock.advance(1.minutes)
        // Gone on the server: one Note of `work`, one of its sub-Folder and one of the Vault root.
        E2e.server.deleteOnServer(e2e.setup.id("Standup"))
        E2e.server.deleteOnServer(e2e.setup.id("alpha"))
        E2e.server.deleteOnServer(e2e.setup.id("Ideas"))

        list.newNote("Fresh")
        note.assertTitle("Fresh")
        note.leave()

        list.assertBreadcrumbAt("work")
        list.assertRow("Fresh")
        list.assertNoRow("Standup")
        list.assertRow("Hiring")
        list.openFolder("projects")
        list.assertRow("alpha")
        list.openBreadcrumb(Strings.TREE_ROOT)
        list.assertRow("Ideas")
        assertTrue(server.requests(Endpoint.LIST).all { it.query["category"] == "work" }, "a LIST outside work")
    }
}
