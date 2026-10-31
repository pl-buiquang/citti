package app.citti.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import app.citti.core.note.FolderNode
import app.citti.core.note.InvalidNameException
import app.citti.core.note.NameError
import app.citti.core.note.buildFolderTree
import app.citti.core.note.find
import app.citti.core.sync.categoryMatchesAny
import app.citti.ui.TestTags
import app.citti.ui.resources.Res
import app.citti.ui.resources.create
import app.citti.ui.resources.folder_error_invalid
import app.citti.ui.resources.folder_error_unsynced
import app.citti.ui.resources.name_error_blank
import app.citti.ui.resources.name_error_chars
import app.citti.ui.resources.name_error_dot
import app.citti.ui.resources.name_error_slash
import app.citti.ui.resources.new_folder
import app.citti.ui.resources.new_folder_path
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * What the Folder picker lists: the local Folders, Hidden ones included (unlike Tree mode), minus anything under an
 * Unsynced folder (none are local anyway). [hidden] are the Hidden prefixes, for the badge.
 */
data class PickerFolders(
    val tree: FolderNode,
    val hidden: List<String>,
)

/** [counts] are the Notes per Folder, Hidden ones included (`countByFolder`). */
fun pickerFolders(
    counts: List<Pair<String, Long>>,
    hidden: List<String>,
    unsynced: List<String>,
    collator: Comparator<String>,
): PickerFolders =
    PickerFolders(
        buildFolderTree(counts.filterNot { (category, _) -> category.isNotEmpty() && categoryMatchesAny(category, unsynced) }, collator),
        hidden,
    )

/**
 * The state of one Folder picker (Move, the New note dialog, M6's Journal folder): the Folder shown ([path], "" is
 * the Vault root) and the "New folder…" field. Immutable: the owning controller keeps it, so it survives
 * recomposition.
 */
data class FolderPickerState(
    val path: String,
    /** The "New folder…" text, null while the field is closed. */
    val newFolder: String? = null,
    val error: NameError? = null,
    /** The path the [error] is about (the Unsynced folder message names it). */
    val errorPath: String = "",
) {
    fun open(path: String) = copy(path = path, newFolder = null, error = null)

    /** Back: closes the "New folder…" field, else goes up one level; null at the Vault root (the picker closes). */
    fun back(): FolderPickerState? =
        when {
            newFolder != null -> copy(newFolder = null, error = null)
            path.isEmpty() -> null
            else -> open(path.substringBeforeLast('/', missingDelimiterValue = ""))
        }

    fun startNewFolder() = copy(newFolder = "", error = null)

    fun onNewFolder(text: String) = copy(newFolder = text, error = null)

    /** The typed path, relative to [path]: what the caller passes to `NameRules.normaliseFolder`. */
    fun typedPath(): String {
        val typed = newFolder.orEmpty().trim()
        return if (path.isEmpty()) typed else "$path/$typed"
    }

    /**
     * The result of normalising [typedPath]: the picker moves into the new Folder (it has no Notes yet, so the tree
     * does not list it), or shows the error. A blank entry is refused.
     */
    fun withNewFolder(normalised: Result<String>): FolderPickerState {
        if (newFolder.isNullOrBlank()) return copy(error = NameError.Blank, errorPath = "")
        val folder = normalised.getOrNull()
        if (folder != null) return open(folder)
        val error = (normalised.exceptionOrNull() as? InvalidNameException)?.error ?: NameError.DotSegment
        return copy(error = error, errorPath = typedPath().trim('/'))
    }
}

/** The text of a refused title or Folder. [path] fills the Unsynced folder message. */
@Composable
fun nameErrorText(
    error: NameError,
    path: String = "",
): String =
    when (error) {
        NameError.Blank -> stringResource(Res.string.name_error_blank)
        NameError.Slash -> stringResource(Res.string.name_error_slash)
        NameError.UnsafeCharacter -> stringResource(Res.string.name_error_chars)
        NameError.LeadingDot -> stringResource(Res.string.name_error_dot)
        NameError.DotSegment -> stringResource(Res.string.folder_error_invalid)
        NameError.Unsynced -> stringResource(Res.string.folder_error_unsynced, path)
    }

/** The user's actions in a [FolderPicker]. */
class FolderPickerActions(
    val onOpen: (path: String) -> Unit,
    val onBack: () -> Unit,
    val onNewFolder: () -> Unit,
    val onNewFolderText: (String) -> Unit,
    val onNewFolderDone: () -> Unit,
    val onConfirm: () -> Unit,
)

/**
 * The full-screen Folder picker ([design-brief] §9): ← and [title], the Breadcrumb, the child Folders (folders
 * only, Hidden ones with their badge), "New folder…" at the end of the list, and [confirmLabel] at the bottom.
 * [folders] null while they load.
 */
@Composable
fun FolderPicker(
    title: String,
    confirmLabel: String,
    state: FolderPickerState,
    folders: PickerFolders?,
    actions: FolderPickerActions,
) {
    val children =
        folders
            ?.tree
            ?.find(state.path)
            ?.children
            .orEmpty()
    Column(
        Modifier
            .fillMaxSize()
            .background(CittiTheme.colors.bg)
            .clickable(enabled = false) {}
            .safeDrawingPadding()
            .imePadding(),
    ) {
        CittiTopBar(title, onBack = actions.onBack)
        Breadcrumb(state.path, onOpen = actions.onOpen, modifier = Modifier.padding(vertical = NotesSpacing.space1))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(TestTags.FOLDER_PICKER)) {
            items(children, key = { it.path }) { folder ->
                FolderRow(
                    folder.name,
                    folder.totalNotes,
                    onClick = { actions.onOpen(folder.path) },
                    hidden = folders != null && categoryMatchesAny(folder.path, folders.hidden),
                )
            }
            item(key = "new-folder") { NewFolderItem(state, actions) }
        }
        Column(Modifier.padding(horizontal = CittiTheme.shape.gutter, vertical = NotesSpacing.space3)) {
            CittiButton(confirmLabel, actions.onConfirm, Modifier.testTag(TestTags.FOLDER_PICKER_CONFIRM))
        }
    }
}

@Composable
private fun NewFolderItem(
    state: FolderPickerState,
    actions: FolderPickerActions,
) {
    val typed = state.newFolder
    if (typed == null) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = CittiTheme.shape.rowMin)
                .clickable(role = Role.Button, onClick = actions.onNewFolder)
                .padding(horizontal = CittiTheme.shape.gutter, vertical = NotesSpacing.space2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space3),
        ) {
            Icon(CittiIcon.Plus, color = CittiTheme.colors.accentInk)
            BasicText(stringResource(Res.string.new_folder), style = CittiTheme.typography.ui.copy(color = CittiTheme.colors.accentInk))
        }
        return
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        Modifier.padding(horizontal = CittiTheme.shape.gutter, vertical = NotesSpacing.space2),
        verticalArrangement = Arrangement.spacedBy(NotesSpacing.space2),
    ) {
        CittiTextField(
            value = typed,
            onValueChange = actions.onNewFolderText,
            label = stringResource(Res.string.new_folder_path),
            modifier = Modifier.testTag(TestTags.FOLDER_PICKER_NEW_FOLDER),
            keyboardType = KeyboardType.Uri,
            error = state.error?.let { nameErrorText(it, state.errorPath) },
            imeAction = ImeAction.Done,
            onImeAction = actions.onNewFolderDone,
            focusRequester = focus,
        )
        CittiButton(stringResource(Res.string.create), actions.onNewFolderDone, kind = ButtonKind.Quiet)
    }
}
