package app.citti.e2e.flows

import app.citti.core.fake.Endpoint
import app.citti.e2e.E2e
import app.citti.e2e.ServerAssert
import app.citti.e2e.robots.DailyRobot
import app.citti.e2e.robots.ListRobot
import app.citti.e2e.robots.NoteRobot
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The Today button (spec M6): today's Daily note opens when it exists, locally or only on the server, and is created
 * from the Template otherwise, in the Date locale detected from the French journal (phone in English).
 */
class DailyNotesTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    private val list = ListRobot(e2e)
    private val daily = DailyRobot(e2e)
    private val note = NoteRobot(e2e)
    private val server = ServerAssert()

    /** Waits for the sync of the app's start, then forgets its requests. */
    private fun started() {
        list.assertRow("Ideas")
        E2e.server.clearRequests()
    }

    @Test
    fun todayOpensTheExistingNote() {
        started()
        daily.tapToday()
        note.assertTitle("2026-10-30")
        note.assertText("vendredi 30 octobre 2026")
        server.assertRequests(Endpoint.CREATE, 0)
    }

    @Test
    fun newDayIsCreatedFromTheTemplateInFrench() {
        started()
        moveTo(OCT_31)
        daily.tapToday()
        note.assertTitle("2026-10-31")
        note.assertEditorFocused()
        note.assertText("# samedi 31 octobre 2026")
        note.assertText("Week 44")

        val created = server.awaitRequest(Endpoint.CREATE)
        assertTrue("\"title\":\"2026-10-31\"" in created.body, created.body)
        server.assertNote("2026-10-31", category = "journal", content = "# samedi 31 octobre 2026\n\nWeek 44\n\n## Notes\n\n")
        server.assertRequests(Endpoint.CREATE, 1)
        assertEquals(
            "fr",
            E2e.container.dailySettings
                .config()
                .localeDetected,
        )
    }

    @Test
    fun todaysNoteMadeElsewhereIsOpenedNotDuplicated() {
        started()
        moveTo(OCT_31)
        E2e.server.addNote("2026-10-31", "# samedi 31 octobre 2026\n\nWritten in Obsidian.", category = "journal")
        list.assertNoRow("2026-10-31")

        daily.tapToday()
        note.assertTitle("2026-10-31")
        note.assertText("Written in Obsidian.")
        server.awaitRequest(Endpoint.LIST) { it.query["category"] == "journal" }
        server.assertRequests(Endpoint.CREATE, 0)
        assertEquals(1, E2e.server.notes.count { it.title.startsWith("2026-10-31") }, "${E2e.server.notes.map { it.title }}")
    }

    @Test
    fun missingTemplateGivesAnEmptyNoteAndSaysSo() {
        started()
        E2e.container.dailySettings.setTemplate("templates/missing.md")
        moveTo(OCT_31)
        daily.tapToday()
        note.assertTitle("2026-10-31")
        daily.assertTemplateMissing("templates/missing.md")

        server.awaitRequest(Endpoint.DAV_GET) { it.path.endsWith("templates/missing.md") }
        server.awaitRequest(Endpoint.CREATE) { "2026-10-31" in it.body }
        server.assertNote("2026-10-31", category = "journal", content = "")
    }

    companion object {
        val OCT_31: Instant = Instant.parse("2026-10-31T09:00:00Z")

        /** Moves the app's clock and the server's to [instant]: they are separate. */
        fun moveTo(instant: Instant) {
            E2e.clock.set(instant)
            E2e.server.now = instant.epochSeconds
        }
    }
}
