package app.citti.e2e.flows

import app.citti.e2e.robots.ListRobot
import app.citti.e2e.robots.Strings
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import org.junit.Rule
import org.junit.Test

class ListTreeTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    private val list = ListRobot(e2e)

    @Test
    fun favoritesComeFirstNewestFirst() {
        list.assertFirstRows("Roadmap", "todo", "Welcome")
    }

    @Test
    fun hiddenFolderIsNotListed() {
        list.assertRow("alpha")
        list.assertNoRow("Old plan")
        list.showTree()
        list.assertFolder("work")
        list.assertNoFolder("archive")
    }

    @Test
    fun treeGoesTwoLevelsDeepWithABreadcrumb() {
        list.showTree()
        list.openFolder("work")
        list.assertBreadcrumbAt("work")
        list.assertRow("Standup")
        list.openFolder("projects")
        list.assertBreadcrumbAt("projects")
        list.assertRow("alpha")
        list.assertNoRow("Standup")

        list.openBreadcrumb(Strings.TREE_ROOT)
        list.assertFolder("personal")
    }
}
