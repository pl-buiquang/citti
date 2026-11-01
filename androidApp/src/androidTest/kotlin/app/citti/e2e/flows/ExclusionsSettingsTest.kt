package app.citti.e2e.flows

import app.citti.core.fake.Endpoint
import app.citti.core.settings.ExclusionKind
import app.citti.e2e.E2e
import app.citti.e2e.ServerAssert
import app.citti.e2e.robots.ListRobot
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

class ExclusionsSettingsTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    private val list = ListRobot(e2e)
    private val server = ServerAssert()

    @Test
    fun hiddenFolderLeavesListAndTreeAtOnceButStaysSearchable() {
        list.assertRow("Travel")
        val settings = list.openSettings()
        settings.addHiddenFolder("personal")
        settings.assertFolderListed(ExclusionKind.HIDDEN, "personal")
        settings.removeHiddenFolder("archive")
        settings.leave()

        list.assertNoRow("Travel")
        list.assertRow("Old plan")
        list.showTree()
        list.assertNoFolder("personal")
        list.assertFolder("archive")

        val search = list.openSearch()
        search.search("Lisbon")
        search.assertResult("Lisbon")
    }

    @Test
    fun unsyncedFolderGoesFromThePhoneAndIsNotDownloadedAgain() {
        val settings = list.openSettings()
        settings.addUnsyncedFolder("work")
        settings.removeUnsyncedFolder("private")
        settings.leave()
        list.assertNoRow("Standup")

        E2e.server.clearRequests()
        list.refresh()
        list.assertRow("Diary")
        list.assertNoRow("Standup")
        list.assertNoRow("alpha")

        val workIds =
            E2e.server.notes
                .filter { it.category.startsWith("work") }
                .map { it.id.toString() }
        assertTrue(server.requests(Endpoint.GET_NOTE) { it.path.substringAfterLast('/') in workIds }.isEmpty())
    }
}
