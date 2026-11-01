package app.citti.e2e.robots

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import app.citti.core.appearance.ColorToken
import app.citti.core.appearance.ModeChoice
import app.citti.core.appearance.ThemeChoice
import app.citti.e2e.rules.E2eRules
import app.citti.ui.TestTags
import app.citti.ui.settings.SettingsSection

/** Settings › Appearance and the theme editor. The root `theme.state` tag reads `<family>:<light|dark>:<accent>`. */
class AppearanceRobot(
    e2e: E2eRules,
) : Robot(e2e) {
    fun chooseTheme(theme: ThemeChoice) {
        openSettingsSection(SettingsSection.APPEARANCE)
        waitFor(hasTestTag(TestTags.appearanceTheme(theme))).performScrollTo().performClick()
    }

    fun chooseMode(mode: ModeChoice) {
        openSettingsSection(SettingsSection.APPEARANCE)
        waitFor(hasTestTag(TestTags.appearanceMode(mode))).performScrollTo().performClick()
    }

    fun editCustomTheme() {
        openSettingsSection(SettingsSection.APPEARANCE)
        waitFor(hasTestTag(TestTags.APPEARANCE_EDIT_CUSTOM)).performScrollTo().performClick()
        waitFor(hasTestTag(TestTags.THEME_EDITOR))
    }

    fun assertEditorShown() {
        waitFor(hasTestTag(TestTags.THEME_EDITOR))
    }

    fun assertState(expected: String) {
        waitUntil("theme.state $expected") { state() == expected }
    }

    private fun state(): String? =
        compose
            .onAllNodes(hasTestTag(TestTags.THEME_STATE), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.getOrNull(SemanticsProperties.StateDescription)

    /** Opens the color sheet of [token], types [hex] and closes the sheet. */
    fun overrideColor(
        token: ColorToken,
        hex: String,
    ) {
        waitFor(hasTestTag(TestTags.themeColor(token))).performScrollTo().performClick()
        waitFor(hasTestTag(TestTags.THEME_HEX), unmerged = true)
        compose
            .onNode(
                androidx.compose.ui.test
                    .hasSetTextAction() and
                    androidx.compose.ui.test
                        .hasAnyAncestor(hasTestTag(TestTags.THEME_HEX)),
                true,
            ).performTextReplacement(hex)
        waitFor(hasTestTag(TestTags.THEME_SHEET_DONE)).performClick()
        waitGone(hasTestTag(TestTags.THEME_COLOR_SHEET))
    }

    fun assertLowContrastWarning() {
        waitFor(hasText("Low contrast", substring = true).and(hasAncestorTag(TestTags.themeColor(ColorToken.INK_MUTED))), unmerged = true)
    }

    fun assertNoLowContrastWarning() {
        waitUntil("no low contrast warning") { !exists(hasText("Low contrast", substring = true), unmerged = true) }
    }

    fun resetColor(
        token: ColorToken,
        name: String,
    ) {
        waitFor(hasTestTag(TestTags.themeColor(token))).performScrollTo()
        waitFor(hasContentDescriptionStarting("Reset $name")).performClick()
    }

    fun resetAll(count: Int) {
        click("Reset all")
        waitFor(hasText(if (count == 1) "Remove 1 override?" else "Remove all $count overrides?"))
        waitFor(hasTestTag(TestTags.THEME_CONFIRM)).performClick()
        waitGone(hasText("Reset all"))
    }

    private fun hasAncestorTag(tag: String) =
        androidx.compose.ui.test
            .hasAnyAncestor(hasTestTag(tag))

    private fun hasContentDescriptionStarting(prefix: String) =
        SemanticsMatcher("content description starts with $prefix") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(prefix) } == true
        }
}
