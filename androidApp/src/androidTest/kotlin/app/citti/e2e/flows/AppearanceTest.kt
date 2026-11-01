package app.citti.e2e.flows

import app.citti.core.appearance.ColorToken
import app.citti.core.appearance.ModeChoice
import app.citti.core.appearance.ThemeChoice
import app.citti.e2e.E2e
import app.citti.e2e.ServerAssert
import app.citti.e2e.robots.AppearanceRobot
import app.citti.e2e.robots.ListRobot
import app.citti.e2e.robots.LoginRobot
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import app.citti.ui.settings.SettingsSection
import org.junit.Rule
import org.junit.Test

/** Settings › Appearance and the theme editor (M8). The Theme file pickers are covered by the controller tests. */
class AppearanceTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    private val list = ListRobot(e2e)
    private val server = ServerAssert()

    @Test
    fun themeAndModeApplyAtOnceAndSurviveRecreate() {
        list.assertRow("Ideas")
        E2e.server.clearRequests()
        val settings = list.openSettings()
        val appearance = AppearanceRobot(e2e)

        appearance.chooseTheme(ThemeChoice.PAPER)
        appearance.chooseMode(ModeChoice.DARK)
        appearance.assertState("paper:dark:theme")
        appearance.chooseMode(ModeChoice.LIGHT)
        appearance.assertState("paper:light:theme")

        e2e.recreate()
        appearance.assertState("paper:light:theme")
        settings.assertSectionShown(SettingsSection.APPEARANCE)
        // M8 sends no request of its own.
        server.assertNoRequests()
    }

    @Test
    fun customThemeSeedsFromThePresetAndWarnsOnLowContrast() {
        list.assertRow("Ideas")
        list.openSettings()
        val appearance = AppearanceRobot(e2e)
        appearance.chooseTheme(ThemeChoice.INDEX)
        appearance.editCustomTheme()
        appearance.assertState("index:light:theme")
        appearance.assertNoLowContrastWarning()

        // A very light muted text on the light background: warned, and still saved.
        appearance.overrideColor(ColorToken.INK_MUTED, "#E0E0E0")
        appearance.assertLowContrastWarning()
        appearance.overrideColor(ColorToken.BG, "#FAFAFA")

        appearance.resetColor(ColorToken.INK_MUTED, "Muted text")
        appearance.assertNoLowContrastWarning()
        appearance.resetAll(count = 1)
        appearance.assertEditorShown()
    }

    @Test
    fun theAppearanceSurvivesSignOutAndSignIn() {
        list.assertRow("Roadmap")
        val settings = list.openSettings()
        val appearance = AppearanceRobot(e2e)
        appearance.chooseTheme(ThemeChoice.PAPER)
        appearance.chooseMode(ModeChoice.DARK)
        settings.signOut()

        val login = LoginRobot(e2e)
        login.assertShown()
        appearance.assertState("paper:dark:theme")
        login.signInManually(E2e.SERVER, E2e.USER, E2e.APP_PASSWORD)
        list.assertRow("Roadmap")
        appearance.assertState("paper:dark:theme")
    }
}
