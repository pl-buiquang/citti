package app.citti.e2e.flows

import app.citti.core.fake.Endpoint
import app.citti.core.fake.FakeFailure
import app.citti.e2e.E2e
import app.citti.e2e.ServerAssert
import app.citti.e2e.robots.ListRobot
import app.citti.e2e.robots.LoginRobot
import app.citti.e2e.robots.Strings
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import org.junit.Rule
import org.junit.Test

class AuthFailureTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    private val list = ListRobot(e2e)
    private val server = ServerAssert()

    @Test
    fun unauthorizedSyncAsksToSignInAgainAndKeepsTheNotes() {
        list.assertRow("Roadmap")
        E2e.server.failNext(FakeFailure.UNAUTHORIZED, endpoint = Endpoint.LIST)
        list.refresh()
        list.assertBanner(Strings.BANNER_AUTH_FAILED)

        list.tapBannerAction(Strings.BANNER_SIGN_IN_AGAIN)
        E2e.server.clearRequests()
        LoginRobot(e2e).signInManually(E2e.SERVER, E2e.USER, E2e.APP_PASSWORD)

        list.assertRow("Roadmap")
        list.assertRow("alpha")
        server.assertRequests(Endpoint.GET_NOTE, 0)
    }
}
