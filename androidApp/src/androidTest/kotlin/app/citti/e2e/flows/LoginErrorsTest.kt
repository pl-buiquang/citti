package app.citti.e2e.flows

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import app.citti.core.fake.Endpoint
import app.citti.core.fake.FakeFailure
import app.citti.e2e.E2e
import app.citti.e2e.ServerAssert
import app.citti.e2e.robots.LoginRobot
import app.citti.e2e.robots.Strings
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import org.junit.Rule
import org.junit.Test

class LoginErrorsTest {
    @get:Rule val e2e = E2eRules(Setup.signedOut())

    private val login = LoginRobot(e2e)

    @Test
    fun unauthorizedKeepsTheLoginScreen() {
        E2e.server.failNext(FakeFailure.UNAUTHORIZED, endpoint = Endpoint.CAPABILITIES)
        login.signInManually(E2e.SERVER, E2e.USER, E2e.APP_PASSWORD)
        login.assertError(Strings.ERROR_WRONG_CREDENTIALS)
        login.assertShown()
    }

    @Test
    fun serverWithoutTheNotesApp() {
        E2e.server.notesAppInstalled = false
        login.signInManually(E2e.SERVER, E2e.USER, E2e.APP_PASSWORD)
        login.assertError(Strings.ERROR_NOTES_MISSING)
    }

    @Test
    fun invalidServerAddress() {
        login.signInManually("   ", E2e.USER, E2e.APP_PASSWORD)
        login.assertError(Strings.ERROR_INVALID_URL)
        ServerAssert().assertNoRequests()
    }

    @Test
    fun serverDownIsAnOfflineError() {
        E2e.server.offline = true
        login.signInManually(E2e.SERVER, E2e.USER, E2e.APP_PASSWORD)
        login.assertError(Strings.ERROR_OFFLINE)
    }

    @Test
    fun cancelWhilePollingReturnsToTheForm() {
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_VIEW)).respondWith(ActivityResult(Activity.RESULT_OK, null))
            login.assertShown()
            login.enterServer(E2e.SERVER)
            login.signInWithBrowser()
            login.assertWaitingForBrowser()
            login.cancel()
            login.assertShown()
        } finally {
            Intents.release()
        }
    }
}
