package app.citti.e2e.flows

import app.citti.e2e.ScreenshotFlow
import app.citti.e2e.robots.ListRobot
import app.citti.e2e.robots.NoteRobot
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import app.citti.e2e.rules.saveScreenshot
import org.junit.Rule
import org.junit.Test

/** The screenshots of the README, taken from the Fixture Vault. Regenerate with `./gradlew e2e -Pe2e.screenshots`. */
@ScreenshotFlow
class ReadmeScreenshots {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    private val list = ListRobot(e2e)
    private val note = NoteRobot(e2e)

    private fun shoot(name: String) {
        e2e.compose.waitForIdle()
        saveScreenshot("screenshots/$name.png")
    }

    @Test
    fun listAndTree() {
        list.assertRow("Ideas")
        shoot("list")
        list.showTree()
        list.assertTreeShown()
        list.assertFolder("work")
        shoot("tree")
    }

    @Test
    fun calendar() {
        list.assertRow("Ideas")
        val calendar = list.showCalendar()
        calendar.assertMonth("October 2026")
        calendar.assertNoteDay("29 October")
        shoot("calendar")
    }

    @Test
    fun search() {
        val search = list.openSearch()
        search.search("cafe")
        search.assertResult("Recipes")
        shoot("search")
    }

    @Test
    fun previewAndEditor() {
        list.openNote("todo")
        note.showPreview()
        note.assertPreviewShows("Buy milk")
        shoot("preview")

        note.showEdit()
        note.assertText("Buy milk")
        shoot("editor")
    }
}
