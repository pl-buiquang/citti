package app.citti.e2e.flows

import app.citti.e2e.robots.ListRobot
import app.citti.e2e.robots.NoteRobot
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import org.junit.Rule
import org.junit.Test

class BackStackRecreateTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    private val list = ListRobot(e2e)
    private val note = NoteRobot(e2e)

    @Test
    fun treeFolderNoteStackSurvivesRecreate() {
        list.showTree()
        list.openFolder("work")
        list.openFolder("projects")
        list.openNote("alpha")
        note.assertTitle("alpha")

        e2e.recreate()
        note.assertTitle("alpha")

        list.pressBack()
        list.assertBreadcrumbAt("projects")
        list.pressBack()
        list.assertBreadcrumbAt("work")
        list.pressBack()
        list.assertTreeShown()
        list.assertFolder("work")
    }

    @Test
    fun treeModeIsKeptAcrossRecreate() {
        list.showTree()
        list.assertFolder("work")
        e2e.recreate()
        list.assertTreeShown()
        list.showList()
        e2e.recreate()
        list.assertListShown()
    }
}
