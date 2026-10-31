package app.citti.e2e.flows

import app.citti.e2e.E2e
import app.citti.e2e.robots.ListRobot
import app.citti.e2e.robots.LoginRobot
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import org.junit.Rule
import org.junit.Test

class SignOutTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    private val list = ListRobot(e2e)

    @Test
    fun signOutWipesTheNotesAndSignInSyncsAgain() {
        list.assertRow("Roadmap")
        list.openSettings().signOut()

        val login = LoginRobot(e2e)
        login.assertShown()
        login.signInManually(E2e.SERVER, E2e.USER, E2e.APP_PASSWORD)

        // Signed out wiped the exclusions too: private/ is synced this time.
        list.assertRow("Diary")
        list.assertRow("Roadmap")
    }
}
