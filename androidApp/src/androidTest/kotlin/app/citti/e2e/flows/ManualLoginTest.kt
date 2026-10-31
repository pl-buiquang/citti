package app.citti.e2e.flows

import app.citti.core.fake.Endpoint
import app.citti.e2e.E2e
import app.citti.e2e.ServerAssert
import app.citti.e2e.robots.ListRobot
import app.citti.e2e.robots.LoginRobot
import app.citti.e2e.robots.Strings
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import org.junit.Rule
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.test.assertEquals

class ManualLoginTest {
    @get:Rule val e2e = E2eRules(Setup.signedOut(fixture = "vault"))

    private val login = LoginRobot(e2e)
    private val list = ListRobot(e2e)
    private val server = ServerAssert()

    @Test
    fun wrongPasswordThenRightOneAndTheAccountSurvivesRecreate() {
        login.signInManually(E2e.SERVER, E2e.USER, "wrong")
        login.assertError(Strings.ERROR_WRONG_CREDENTIALS)

        login.enterAppPassword(E2e.APP_PASSWORD)
        login.signIn()
        list.assertRow("Roadmap")

        e2e.recreate()
        list.assertRow("Roadmap")

        val basic = "Basic " + Base64.encode("${E2e.USER}:${E2e.APP_PASSWORD}".encodeToByteArray())
        assertEquals(basic, server.requests(Endpoint.CAPABILITIES).last().header("Authorization"))
        assertEquals(basic, server.requests(Endpoint.LIST).first().header("Authorization"))
    }
}
