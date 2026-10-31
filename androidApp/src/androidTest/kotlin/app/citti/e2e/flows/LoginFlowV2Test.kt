package app.citti.e2e.flows

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import app.citti.core.fake.Endpoint
import app.citti.e2e.E2e
import app.citti.e2e.ServerAssert
import app.citti.e2e.robots.ListRobot
import app.citti.e2e.robots.LoginRobot
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

/** Login Flow v2: the browser is stubbed, the poll answers 404 until the test approves. */
class LoginFlowV2Test {
    @get:Rule val e2e = E2eRules(Setup.signedOut(fixture = "vault"))

    private val login = LoginRobot(e2e)
    private val server = ServerAssert()

    @Before
    fun stubBrowser() {
        Intents.init()
        intending(hasAction(Intent.ACTION_VIEW)).respondWith(ActivityResult(Activity.RESULT_OK, null))
    }

    @After
    fun releaseIntents() = Intents.release()

    @Test
    fun approvedInTheBrowserThenListShown() {
        login.assertShown()
        login.enterServer(E2e.SERVER)
        login.signInWithBrowser()
        login.assertWaitingForBrowser()
        intended(hasData("${E2e.SERVER}/index.php/login/v2/flow/fake-login-token"))

        server.awaitRequest(Endpoint.LOGIN_POLL)
        E2e.server.approveLogin(E2e.USER, E2e.APP_PASSWORD, pollsBeforeSuccess = 1)

        ListRobot(e2e).assertRow("Roadmap")
        server.assertRequests(Endpoint.LOGIN_START, 1)
        assertTrue(server.requests(Endpoint.LOGIN_POLL).size >= 3, "polls: ${server.requests(Endpoint.LOGIN_POLL).size}")
        server.awaitRequest(Endpoint.LIST)
    }
}
