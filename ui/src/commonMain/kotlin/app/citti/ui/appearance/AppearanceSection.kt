package app.citti.ui.appearance

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.citti.core.appearance.AccentSource
import app.citti.core.appearance.Appearance
import app.citti.core.appearance.ModeChoice
import app.citti.core.appearance.ThemeChoice
import app.citti.ui.TestTags
import app.citti.ui.components.ButtonKind
import app.citti.ui.components.CittiButton
import app.citti.ui.components.colored
import app.citti.ui.resources.Res
import app.citti.ui.resources.appearance_accent
import app.citti.ui.resources.appearance_accent_dynamic
import app.citti.ui.resources.appearance_accent_help
import app.citti.ui.resources.appearance_accent_theme
import app.citti.ui.resources.appearance_edit_custom
import app.citti.ui.resources.appearance_mode
import app.citti.ui.resources.appearance_mode_dark
import app.citti.ui.resources.appearance_mode_light
import app.citti.ui.resources.appearance_mode_system
import app.citti.ui.resources.appearance_theme
import app.citti.ui.resources.appearance_theme_custom
import app.citti.ui.resources.appearance_theme_index
import app.citti.ui.resources.appearance_theme_paper
import app.citti.ui.resources.appearance_theme_signal
import app.citti.ui.resources.settings_appearance
import app.citti.ui.settings.Help
import app.citti.ui.settings.OptionRow
import app.citti.ui.settings.SectionHeader
import app.citti.ui.theme.CittiTheme
import org.jetbrains.compose.resources.stringResource

/** The actions of Settings › Appearance; the app binds them to a `SettingsController`. */
class AppearanceActions(
    val onTheme: (ThemeChoice) -> Unit,
    val onMode: (ModeChoice) -> Unit,
    val onAccent: (AccentSource) -> Unit,
    val onEditCustom: () -> Unit,
)

/** Settings › Appearance: Theme, Mode, Accent source and the entry to the theme editor. */
@Composable
fun AppearanceSection(
    appearance: Appearance,
    actions: AppearanceActions,
) {
    SectionHeader(Res.string.settings_appearance)
    BasicText(stringResource(Res.string.appearance_theme), style = CittiTheme.typography.meta.colored(muted = true))
    val themes =
        listOf(
            ThemeChoice.SIGNAL to Res.string.appearance_theme_signal,
            ThemeChoice.PAPER to Res.string.appearance_theme_paper,
            ThemeChoice.INDEX to Res.string.appearance_theme_index,
            ThemeChoice.CUSTOM to Res.string.appearance_theme_custom,
        )
    for ((choice, label) in themes) {
        OptionRow(stringResource(label), appearance.theme == choice, Modifier.testTag(TestTags.appearanceTheme(choice))) {
            actions.onTheme(choice)
        }
    }
    BasicText(stringResource(Res.string.appearance_mode), style = CittiTheme.typography.meta.colored(muted = true))
    val modes =
        listOf(
            ModeChoice.SYSTEM to Res.string.appearance_mode_system,
            ModeChoice.LIGHT to Res.string.appearance_mode_light,
            ModeChoice.DARK to Res.string.appearance_mode_dark,
        )
    for ((choice, label) in modes) {
        OptionRow(stringResource(label), appearance.mode == choice, Modifier.testTag(TestTags.appearanceMode(choice))) {
            actions.onMode(choice)
        }
    }
    BasicText(stringResource(Res.string.appearance_accent), style = CittiTheme.typography.meta.colored(muted = true))
    OptionRow(stringResource(Res.string.appearance_accent_theme), appearance.accent == AccentSource.THEME) {
        actions.onAccent(AccentSource.THEME)
    }
    OptionRow(stringResource(Res.string.appearance_accent_dynamic), appearance.accent == AccentSource.DYNAMIC) {
        actions.onAccent(AccentSource.DYNAMIC)
    }
    Help(Res.string.appearance_accent_help)
    CittiButton(
        stringResource(Res.string.appearance_edit_custom),
        actions.onEditCustom,
        Modifier.testTag(TestTags.APPEARANCE_EDIT_CUSTOM),
        kind = ButtonKind.Quiet,
    )
}
