package app.citti.e2e.flows

import app.citti.core.fake.Endpoint
import app.citti.e2e.E2e
import app.citti.e2e.ServerAssert
import app.citti.e2e.Smoke
import app.citti.e2e.robots.ListRobot
import app.citti.e2e.robots.LoginRobot
import app.citti.e2e.robots.NoteRobot
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

/** The main path in one run, to check that shrinking strips nothing the app needs. */
@Smoke
class SmokeTest {
    @get:Rule val e2e = E2eRules(Setup.signedOut(fixture = "vault"))

    private val login = LoginRobot(e2e)
    private val list = ListRobot(e2e)
    private val note = NoteRobot(e2e)
    private val server = ServerAssert()

    @Test
    fun signInSyncReadAnImageThenEditAndPush() {
        login.signInManually(E2e.SERVER, E2e.USER, E2e.APP_PASSWORD)
        list.assertRow("Roadmap")

        list.openNote("images")
        note.showPreview()
        note.assertImageLoaded("by note")
        note.pressBack()

        list.openNote("todo")
        note.typeAtEnd("\nSmoke line")
        server.awaitRequest(Endpoint.UPDATE) { "Smoke line" in it.body }
        assertTrue("Smoke line" in E2e.server.note(e2e.setup.id("todo"))!!.content)
    }
}
