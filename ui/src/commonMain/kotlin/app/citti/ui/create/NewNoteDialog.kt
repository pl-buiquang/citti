package app.citti.ui.create

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
import app.citti.ui.resources.change
import app.citti.ui.resources.choose
import app.citti.ui.resources.choose_folder
import app.citti.ui.resources.create
import app.citti.ui.resources.new_note
import app.citti.ui.resources.new_note_folder
import app.citti.ui.resources.new_note_title_hint
import app.citti.ui.resources.title_clash_create
import app.citti.ui.resources.title_clash_open
import app.citti.ui.resources.title_clash_title
import app.citti.ui.resources.tree_root
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSpacing
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.stringResource

/** Every action of the New note flow, bound to a [NewNoteController] by [NewNoteController.actions]. */
class NewNoteActions(
    val onTitle: (String) -> Unit,
    val onCreate: () -> Unit,
    val onChangeFolder: () -> Unit,
    val onDismiss: () -> Unit,
    val onOpenExisting: () -> Unit,
    val onCreateFreeTitle: () -> Unit,
    val picker: FolderPickerActions,
)

fun NewNoteController.actions(): NewNoteActions =
    NewNoteActions(
        onTitle = ::onTitle,
        onCreate = ::create,
        onChangeFolder = ::changeFolder,
        onDismiss = ::dismiss,
        onOpenExisting = ::openExisting,
        onCreateFreeTitle = ::createFreeTitle,
        picker =
            FolderPickerActions(
                onOpen = ::pickerOpen,
                onBack = ::back,
                onNewFolder = ::pickerNewFolder,
                onNewFolderText = ::pickerNewFolderText,
                onNewFolderDone = ::pickerNewFolderDone,
                onConfirm = ::pickerConfirm,
            ),
    )

/**
 * The New note dialog, the title-clash dialog or the Folder picker, over the screen that hosts it. [folders] is
 * collected only while the picker is open.
 */
@Composable
fun NewNoteHost(
    state: NewNoteState?,
    folders: Flow<PickerFolders>,
    actions: NewNoteActions,
) {
    val s = state ?: return
    val picker = s.picker
    when {
        picker != null -> {
            val shown by remember(folders) { folders }.collectAsState(null)
            FolderPicker(
                title = stringResource(Res.string.choose_folder),
                confirmLabel = stringResource(Res.string.choose),
                state = picker,
                folders = shown,
                actions = actions.picker,
            )
        }

        s.clash != null -> {
            TitleClashDialog(s.clash, s.checking, actions)
        }

        else -> {
            NewNoteDialog(s, actions)
        }
    }
}

/** Title field (focused, Done = Create), the Folder with Change, Cancel and Create (inline progress while checking). */
@Composable
fun NewNoteDialog(
    state: NewNoteState,
    actions: NewNoteActions,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val folderName = state.folder.ifEmpty { stringResource(Res.string.tree_root) }
    CittiDialog(stringResource(Res.string.new_note), onDismiss = actions.onDismiss) {
        CittiTextField(
            value = state.title,
            onValueChange = actions.onTitle,
            label = stringResource(Res.string.new_note_title_hint),
            modifier = Modifier.testTag(TestTags.NEW_NOTE_TITLE),
            enabled = !state.checking,
            error = state.error?.let { nameErrorText(it, state.errorPath.orEmpty()) },
            imeAction = ImeAction.Done,
            onImeAction = actions.onCreate,
            focusRequester = focus,
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space2),
        ) {
            BasicText(
                stringResource(Res.string.new_note_folder, folderName),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                style = CittiTheme.typography.meta.colored(muted = true),
            )
            CittiTextButton(
                stringResource(Res.string.change),
                actions.onChangeFolder,
                Modifier.testTag(TestTags.NEW_NOTE_CHANGE_FOLDER),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space2)) {
            CittiButton(stringResource(Res.string.action_cancel), actions.onDismiss, Modifier.weight(1f), kind = ButtonKind.Quiet)
            CittiButton(
                stringResource(Res.string.create),
                actions.onCreate,
                Modifier.weight(1f).testTag(TestTags.NEW_NOTE_CREATE),
                busy = state.checking,
            )
        }
    }
}

/** "A note with this title exists": Open existing, or Create “Title (2)”. */
@Composable
fun TitleClashDialog(
    clash: Clash,
    checking: Boolean,
    actions: NewNoteActions,
) {
    CittiDialog(stringResource(Res.string.title_clash_title), onDismiss = actions.onDismiss) {
        CittiButton(
            stringResource(Res.string.title_clash_open),
            actions.onOpenExisting,
            Modifier.testTag(TestTags.TITLE_CLASH_OPEN),
            kind = ButtonKind.Quiet,
        )
        CittiButton(
            stringResource(Res.string.title_clash_create, clash.freeTitle),
            actions.onCreateFreeTitle,
            Modifier.testTag(TestTags.TITLE_CLASH_CREATE),
            busy = checking,
        )
        CittiTextButton(stringResource(Res.string.action_cancel), actions.onDismiss, Modifier.align(Alignment.End))
    }
}
