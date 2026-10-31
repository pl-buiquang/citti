package app.citti.e2e.robots

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import app.citti.e2e.rules.E2eRules
import app.citti.ui.TestTags

class LoginRobot(
    e2e: E2eRules,
) : Robot(e2e) {
    /** The login screen, in its browser or its manual (app password) form. */
    fun assertShown() {
        waitFor(button(Strings.SIGN_IN_BROWSER) or button(Strings.SIGN_IN))
    }

    fun enterServer(url: String) = type(TestTags.LOGIN_SERVER, url)

    fun signInWithBrowser() = click(Strings.SIGN_IN_BROWSER)

    fun assertWaitingForBrowser() {
        waitFor(hasText(Strings.WAITING_BROWSER))
    }

    fun cancel() = click(Strings.CANCEL)

    /** Switches to the manual form (app password). */
    fun useAppPassword() = click(Strings.USE_APP_PASSWORD)

    fun enterUser(user: String) = type(TestTags.LOGIN_USER, user)

    fun enterAppPassword(password: String) = type(TestTags.LOGIN_APP_PASSWORD, password)

    fun signIn() = click(Strings.SIGN_IN)

    /** Fills the manual form and signs in. */
    fun signInManually(
        server: String,
        user: String,
        password: String,
    ) {
        assertShown()
        useAppPassword()
        enterServer(server)
        enterUser(user)
        enterAppPassword(password)
        signIn()
    }

    fun assertError(message: String) {
        waitFor(hasText(message))
    }

    private fun type(
        tag: String,
        text: String,
    ) {
        waitFor(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag)), unmerged = true).performTextReplacement(text)
    }
}
