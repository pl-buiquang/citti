package app.citti.e2e.flows

import app.citti.e2e.Smoke
import app.citti.e2e.robots.ListRobot
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import app.citti.ui.settings.SettingsSection
import org.junit.Rule
import org.junit.Test

/** Settings is a menu: each entry opens its own page, Back leads to the menu, and the menu shows the version. */
class SettingsMenuTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    private val list = ListRobot(e2e)

    @Test
    @Smoke
    fun everyMenuEntryOpensItsPageAndBackLeadsToTheMenu() {
        list.assertRow("Ideas")
        val settings = list.openSettings()
        settings.assertVersionShown()
        for (section in SettingsSection.entries) {
            settings.openSection(section)
            settings.assertSectionShown(section)
            settings.backToMenu()
        }
        settings.assertVersionShown()
        settings.leave()
        list.assertRow("Ideas")
    }
}
