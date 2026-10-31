package app.citti.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import app.citti.core.note.InfoState
import app.citti.core.note.NoteInfo
import app.citti.ui.TestTags
import app.citti.ui.components.ButtonKind
import app.citti.ui.components.CittiButton
import app.citti.ui.components.CittiDialog
import app.citti.ui.components.CittiTextButton
import app.citti.ui.components.CittiTextField
import app.citti.ui.components.FolderPicker
import app.citti.ui.components.FolderPickerActions
import app.citti.ui.components.PickerFolders
import app.citti.ui.components.colored
import app.citti.ui.components.nameErrorText
import app.citti.ui.resources.Res
import app.citti.ui.resources.action_cancel
import app.citti.ui.resources.clash_error
import app.citti.ui.resources.delete_body
import app.citti.ui.resources.delete_title
import app.citti.ui.resources.info_characters
import app.citti.ui.resources.info_favorite
import app.citti.ui.resources.info_folder
import app.citti.ui.resources.info_hidden_in
import app.citti.ui.resources.info_lines
import app.citti.ui.resources.info_modified
import app.citti.ui.resources.info_no
import app.citti.ui.resources.info_open_conflict
import app.citti.ui.resources.info_readonly
import app.citti.ui.resources.info_size
import app.citti.ui.resources.info_state
import app.citti.ui.resources.info_state_conflict
import app.citti.ui.resources.info_state_pending
import app.citti.ui.resources.info_state_synced
import app.citti.ui.resources.info_title
import app.citti.ui.resources.info_words
import app.citti.ui.resources.info_yes
import app.citti.ui.resources.menu_delete
import app.citti.ui.resources.menu_note_info
import app.citti.ui.resources.move_here
import app.citti.ui.resources.move_title
import app.citti.ui.resources.rename_action
import app.citti.ui.resources.rename_title
import app.citti.ui.resources.size_bytes
import app.citti.ui.resources.size_kb
import app.citti.ui.resources.size_mb_decimal
import app.citti.ui.resources.tree_root
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSpacing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.jetbrains.compose.resources.stringResource

/** The actions of the ⋮ menu's dialogs, bound to a [NoteController] by [NoteController.dialogActions]. */
class NoteDialogActions(
    val onRenameText: (String) -> Unit,
    val onConfirmRename: () -> Unit,
    val onConfirmDelete: () -> Unit,
    val onOpenConflict: () -> Unit,
    val onDismiss: () -> Unit,
    val picker: FolderPickerActions,
)

fun NoteController.dialogActions(): NoteDialogActions =
    NoteDialogActions(
        onRenameText = ::onRenameText,
        onConfirmRename = ::confirmRename,
        onConfirmDelete = ::confirmDelete,
        onOpenConflict = ::openInfoConflict,
        onDismiss = ::dismissDialog,
        picker =
            FolderPickerActions(
                onOpen = ::pickerOpen,
                onBack = { back() },
                onNewFolder = ::pickerNewFolder,
                onNewFolderText = ::pickerNewFolderText,
                onNewFolderDone = ::pickerNewFolderDone,
                onConfirm = ::confirmMove,
            ),
    )

/**
 * The dialog of a ⋮ menu item over the Note screen. [folders] is collected only while the Move picker is open.
 * [dateTime] formats Note info's last modified (epoch seconds) in the device's zone and locale.
 */
@Composable
fun NoteDialogHost(
    dialog: NoteDialog?,
    title: String,
    folders: Flow<PickerFolders>?,
    dateTime: (Long) -> String,
    actions: NoteDialogActions,
) {
    when (dialog) {
        null -> Unit
        is NoteDialog.Rename -> RenameDialog(dialog, actions)
        is NoteDialog.Move -> MoveDialog(dialog, title, folders, actions)
        NoteDialog.DeleteConfirm -> DeleteDialog(title, actions)
        is NoteDialog.Info -> NoteInfoSheet(dialog.info, dateTime, actions)
    }
}

/** The text shown for a Folder path: the Vault root is "Notes". */
@Composable
private fun folderName(folder: String) = folder.ifEmpty { stringResource(Res.string.tree_root) }

/** Rename: the title prefilled and selected, the error under the field, Cancel and Rename (progress while checking). */
@Composable
private fun RenameDialog(
    dialog: NoteDialog.Rename,
    actions: NoteDialogActions,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // The selection is the field's own; the controller holds the text.
    var field by remember { mutableStateOf(TextFieldValue(dialog.text, TextRange(0, dialog.text.length))) }
    if (field.text != dialog.text) field = TextFieldValue(dialog.text, TextRange(dialog.text.length))
    val error =
        when {
            dialog.clashFolder != null -> stringResource(Res.string.clash_error, dialog.text.trim(), folderName(dialog.clashFolder))
            dialog.error != null -> nameErrorText(dialog.error)
            else -> null
        }
    CittiDialog(stringResource(Res.string.rename_title), onDismiss = actions.onDismiss) {
        CittiTextField(
            value = field,
            onValueChange = {
                field = it
                actions.onRenameText(it.text)
            },
            label = stringResource(Res.string.info_title),
            modifier = Modifier.testTag(TestTags.RENAME_FIELD),
            enabled = !dialog.checking,
            error = error,
            imeAction = ImeAction.Done,
            onImeAction = actions.onConfirmRename,
            focusRequester = focus,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space2)) {
            CittiButton(stringResource(Res.string.action_cancel), actions.onDismiss, Modifier.weight(1f), kind = ButtonKind.Quiet)
            CittiButton(
                stringResource(Res.string.rename_action),
                actions.onConfirmRename,
                Modifier.weight(1f).testTag(TestTags.RENAME_CONFIRM),
                busy = dialog.checking,
            )
        }
    }
}

/** Move: the full-screen Folder picker with "Move here"; a clash or a refused Folder shows above the button. */
@Composable
private fun MoveDialog(
    dialog: NoteDialog.Move,
    title: String,
    folders: Flow<PickerFolders>?,
    actions: NoteDialogActions,
) {
    val shown by remember(folders) { folders ?: emptyFlow() }.collectAsState(null)
    val error =
        when {
            dialog.clashFolder != null -> stringResource(Res.string.clash_error, title, folderName(dialog.clashFolder))
            dialog.error != null -> nameErrorText(dialog.error, dialog.errorPath)
            else -> null
        }
    FolderPicker(
        title = stringResource(Res.string.move_title),
        confirmLabel = stringResource(Res.string.move_here),
        state = dialog.picker,
        folders = shown,
        actions = actions.picker,
        error = error,
        busy = dialog.checking,
    )
}

/** "Delete “T”? It moves to the server's trash.", Cancel and a `danger` Delete. */
@Composable
private fun DeleteDialog(
    title: String,
    actions: NoteDialogActions,
) {
    CittiDialog(stringResource(Res.string.delete_title, title), onDismiss = actions.onDismiss) {
        BasicText(stringResource(Res.string.delete_body), style = CittiTheme.typography.body.colored(muted = true))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space2)) {
            CittiButton(stringResource(Res.string.action_cancel), actions.onDismiss, Modifier.weight(1f), kind = ButtonKind.Quiet)
            CittiButton(
                stringResource(Res.string.menu_delete),
                actions.onConfirmDelete,
                Modifier.weight(1f).testTag(TestTags.DELETE_CONFIRM),
                kind = ButtonKind.Danger,
            )
        }
    }
}

/**
 * Note info ([design-brief] §10): a bottom sheet on `surfaceRaised` with key/value rows in `meta` + `rowTitle`, and
 * "Open conflict copy" when there is one. Metadata only. A tap outside dismisses it.
 */
@Composable
private fun NoteInfoSheet(
    info: NoteInfo,
    dateTime: (Long) -> String,
    actions: NoteDialogActions,
) {
    val colors = CittiTheme.colors
    val shape = RoundedCornerShape(topStart = CittiTheme.shape.control, topEnd = CittiTheme.shape.control)
    val yes = stringResource(Res.string.info_yes)
    val no = stringResource(Res.string.info_no)
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = actions.onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.surfaceRaised, shape)
                .border(Dp.Hairline, colors.line, shape)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .verticalScroll(rememberScrollState())
                .padding(vertical = NotesSpacing.space3)
                .testTag(TestTags.NOTE_INFO),
        ) {
            BasicText(
                stringResource(Res.string.menu_note_info),
                Modifier.padding(horizontal = CittiTheme.shape.gutter, vertical = NotesSpacing.space2),
                style = CittiTheme.typography.title.colored(),
            )
            InfoRow(stringResource(Res.string.info_title), info.title)
            InfoRow(stringResource(Res.string.info_folder), folderName(info.folder))
            info.hiddenUnder?.let {
                BasicText(
                    stringResource(Res.string.info_hidden_in, it),
                    Modifier.padding(horizontal = CittiTheme.shape.gutter),
                    style = CittiTheme.typography.meta.colored(muted = true),
                )
            }
            InfoRow(stringResource(Res.string.info_modified), dateTime(info.modified))
            InfoRow(stringResource(Res.string.info_size), sizeText(info.bytes))
            InfoRow(stringResource(Res.string.info_words), info.words.toString())
            InfoRow(stringResource(Res.string.info_characters), info.chars.toString())
            InfoRow(stringResource(Res.string.info_lines), info.lines.toString())
            InfoRow(
                stringResource(Res.string.info_state),
                stringResource(
                    when (info.state) {
                        InfoState.Synced -> Res.string.info_state_synced
                        InfoState.Pending -> Res.string.info_state_pending
                        InfoState.ConflictCopy -> Res.string.info_state_conflict
                    },
                ),
            )
            InfoRow(stringResource(Res.string.info_favorite), if (info.favorite) yes else no)
            InfoRow(stringResource(Res.string.info_readonly), if (info.readonly) yes else no)
            info.conflictCopy?.let { (_, copyTitle) ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = CittiTheme.shape.gutter),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(copyTitle, Modifier.weight(1f), style = CittiTheme.typography.meta.colored(muted = true))
                    CittiTextButton(stringResource(Res.string.info_open_conflict), actions.onOpenConflict)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    key: String,
    value: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = CittiTheme.shape.rowMin)
            .padding(horizontal = CittiTheme.shape.gutter, vertical = NotesSpacing.space1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space3),
    ) {
        BasicText(key, style = CittiTheme.typography.meta.colored(muted = true))
        BasicText(
            value,
            Modifier.weight(1f),
            style =
                CittiTheme.typography.rowTitle
                    .colored()
                    .copy(textAlign = TextAlign.End),
        )
    }
}

/** "812 B", "4.2 KB", "1.3 MB" (1024-based, one decimal). */
@Composable
private fun sizeText(bytes: Int): String {
    val (unit, tenths) = sizeTenths(bytes)
    return when (unit) {
        SizeUnit.B -> stringResource(Res.string.size_bytes, bytes)
        SizeUnit.KB -> stringResource(Res.string.size_kb, tenths.tenthsText())
        SizeUnit.MB -> stringResource(Res.string.size_mb_decimal, tenths.tenthsText())
    }
}

internal enum class SizeUnit { B, KB, MB }

/** The unit of [bytes] and its value in tenths of that unit, rounded half up. */
internal fun sizeTenths(bytes: Int): Pair<SizeUnit, Long> {
    val b = bytes.toLong()
    if (b < KIB) return SizeUnit.B to b * 10
    val kb = (b * 10 + KIB / 2) / KIB
    // 1 048 575 bytes rounds to 1024.0 KB: shown as 1.0 MB instead.
    return if (kb < KIB * 10) SizeUnit.KB to kb else SizeUnit.MB to (b * 10 + KIB * KIB / 2) / (KIB * KIB)
}

internal fun Long.tenthsText(): String = "${this / 10}.${this % 10}"

private const val KIB = 1024L
