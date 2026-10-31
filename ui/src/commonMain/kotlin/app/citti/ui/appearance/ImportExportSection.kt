package app.citti.ui.appearance

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.citti.ui.TestTags
import app.citti.ui.components.ButtonKind
import app.citti.ui.components.CittiButton
import app.citti.ui.components.CittiDialog
import app.citti.ui.components.colored
import app.citti.ui.resources.Res
import app.citti.ui.resources.theme_editor_cancel
import app.citti.ui.resources.theme_editor_export
import app.citti.ui.resources.theme_editor_import
import app.citti.ui.resources.theme_editor_import_export
import app.citti.ui.resources.theme_editor_import_replace
import app.citti.ui.resources.theme_editor_import_replace_body
import app.citti.ui.resources.theme_editor_import_replace_title
import app.citti.ui.resources.theme_editor_remove
import app.citti.ui.resources.theme_editor_reset_all_confirm
import app.citti.ui.settings.SectionHeader
import app.citti.ui.theme.CittiTheme
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Import and export of the Theme file through the system file picker. */
@Composable
internal fun ImportExportSection(actions: ThemeEditorActions) {
    SectionHeader(Res.string.theme_editor_import_export)
    CittiButton(
        stringResource(Res.string.theme_editor_import),
        actions.onImport,
        Modifier.testTag(TestTags.THEME_IMPORT),
        kind = ButtonKind.Quiet,
    )
    CittiButton(
        stringResource(Res.string.theme_editor_export),
        actions.onExport,
        Modifier.testTag(TestTags.THEME_EXPORT),
        kind = ButtonKind.Quiet,
    )
}

/** "Remove all N overrides?" and "Replace your custom theme?". */
@Composable
internal fun EditorDialogs(
    state: ThemeEditorState,
    actions: ThemeEditorActions,
) {
    if (state.confirmResetAll) {
        CittiDialog(
            pluralStringResource(Res.plurals.theme_editor_reset_all_confirm, state.overrideCount, state.overrideCount),
            actions.onDismissResetAll,
        ) {
            CittiButton(
                stringResource(Res.string.theme_editor_remove),
                actions.onConfirmResetAll,
                Modifier.testTag(TestTags.THEME_CONFIRM),
                kind = ButtonKind.Danger,
            )
            CittiButton(stringResource(Res.string.theme_editor_cancel), actions.onDismissResetAll, kind = ButtonKind.Quiet)
        }
    }
    if (state.pendingImport != null) {
        CittiDialog(stringResource(Res.string.theme_editor_import_replace_title), actions.onDismissImport) {
            BasicText(stringResource(Res.string.theme_editor_import_replace_body), style = CittiTheme.typography.body.colored())
            CittiButton(
                stringResource(Res.string.theme_editor_import_replace),
                actions.onConfirmImport,
                Modifier.testTag(TestTags.THEME_CONFIRM),
                kind = ButtonKind.Danger,
            )
            CittiButton(stringResource(Res.string.theme_editor_cancel), actions.onDismissImport, kind = ButtonKind.Quiet)
        }
    }
}
