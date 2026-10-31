package app.citti.e2e.flows

import app.citti.core.fake.Endpoint
import app.citti.core.settings.ExclusionKind
import app.citti.e2e.E2e
import app.citti.e2e.ServerAssert
import app.citti.e2e.robots.ListRobot
import app.citti.e2e.robots.Strings
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Settings › Daily notes: Import from Obsidian, and the Journal folder that can never be an Unsynced folder. */
class DailySettingsTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    private val list = ListRobot(e2e)
    private val server = ServerAssert()

    /** Waits for the sync of the app's start, then forgets its requests. */
    private fun started() {
        list.assertRow("Ideas")
        E2e.server.clearRequests()
    }

    @Test
    fun importFromObsidianAppliesWhatItCanAndSaysWhy() {
        started()
        // The Journal folder is now "private", an Unsynced folder: refused; the rest applies.
        E2e.server.putFile(
            ".obsidian/daily-notes.json",
            """{"folder": "private", "format": "DD-MM-YYYY", "template": "templates/daily"}""".encodeToByteArray(),
        )
        val settings = list.openSettings()
        settings.importFromObsidian()
        settings.assertImportReport("Template templates/daily.md, Format DD-MM-YYYY")
        settings.assertImportReport(Strings.importRefused("private", Strings.journalUnsyncedRefused("private")))
        settings.dismissImportReport()

        server.assertRequests(Endpoint.DAV_GET, 1) { it.path.endsWith(".obsidian/daily-notes.json") }
        val config = E2e.container.dailySettings.config()
        assertEquals("journal", config.folder)
        assertEquals("DD-MM-YYYY", config.format)
        assertEquals("templates/daily.md", config.template)
    }

    @Test
    fun unsyncedFolderCoveringTheJournalIsRefused() {
        started()
        val settings = list.openSettings()
        settings.addUnsyncedFolderRefused("journal", Strings.exclusionJournalRefused("journal"))
        settings.assertFolderNotListed(ExclusionKind.UNSYNCED, "journal")
        settings.pressBack()
        list.assertRow("2026-10-30")

        server.assertRequests(Endpoint.DELETE, 0)
        server.assertNote("2026-10-30", category = "journal")
    }
}
