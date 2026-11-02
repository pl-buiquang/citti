package app.citti.e2e.flows

import app.citti.core.appearance.ModeChoice
import app.citti.core.appearance.ThemeChoice
import app.citti.e2e.E2e
import app.citti.e2e.ScreenshotFlow
import app.citti.e2e.robots.ListRobot
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import app.citti.e2e.rules.saveScreenshot
import org.junit.Rule
import org.junit.Test

/** The dark screenshot of the README: Paper in Dark mode, set before the app starts. */
@ScreenshotFlow
class ReadmeDarkScreenshot {
    @get:Rule val e2e =
        E2eRules(
            Setup.signedInAndSynced(
                extra = {
                    E2e.container.appearanceSettings.apply {
                        setTheme(ThemeChoice.PAPER)
                        setMode(ModeChoice.DARK)
                    }
                },
            ),
        )

    @Test
    fun dark() {
        ListRobot(e2e).assertRow("Ideas")
        e2e.compose.waitForIdle()
        saveScreenshot("screenshots/dark.png")
    }
}
