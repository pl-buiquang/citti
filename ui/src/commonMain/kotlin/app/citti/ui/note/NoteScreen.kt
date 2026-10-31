package app.citti.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.citti.core.markdown.Task
import app.citti.core.markdown.ToolbarAction
import app.citti.ui.components.Banner
import app.citti.ui.components.BannerKind
import app.citti.ui.components.CittiIcon
import app.citti.ui.components.Gutter
import app.citti.ui.components.IconButton
import app.citti.ui.components.StateLabel
import app.citti.ui.components.colored
import app.citti.ui.markdown.EditorState
import app.citti.ui.markdown.FormatToolbar
import app.citti.ui.markdown.MarkdownEditor
import app.citti.ui.markdown.MarkdownPreview
import app.citti.ui.resources.Res
import app.citti.ui.resources.action_back
import app.citti.ui.resources.action_open
import app.citti.ui.resources.banner_auth_failed
import app.citti.ui.resources.banner_sign_in_again
import app.citti.ui.resources.conflict_banner
import app.citti.ui.resources.conflict_copy_of
import app.citti.ui.resources.note_downloading
import app.citti.ui.resources.note_edit
import app.citti.ui.resources.note_not_downloaded
import app.citti.ui.resources.note_preview
import app.citti.ui.resources.note_readonly
import app.citti.ui.resources.note_redo
import app.citti.ui.resources.note_undo
import app.citti.ui.resources.save_failed
import app.citti.ui.resources.save_offline
import app.citti.ui.resources.save_pending
import app.citti.ui.resources.save_saved
import app.citti.ui.resources.save_syncing
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSize
import app.citti.ui.theme.NotesSpacing
import org.jetbrains.compose.resources.stringResource

class NoteActions(
    val onBack: () -> Unit,
    val onTogglePreview: () -> Unit,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onToolbar: (ToolbarAction) -> Unit,
    val onTask: (Task) -> Unit,
    val onLink: (LinkRef) -> Unit,
    val onOpenConflict: () -> Unit,
    val onSignInAgain: () -> Unit,
)

/**
 * The Note screen ([design-brief] §5, §6): top bar (←, title and save indicator, ↶ ↷ while the editor has focus,
 * the Edit/Preview toggle), at most one banner (Conflict, then auth failure), the body (the editor in Edit mode, the
 * Preview in Preview mode, "Downloading…" while the text is not on the device) and the format toolbar above the
 * keyboard while the editor has focus. [previewListState] is owned by the caller so the reading position survives
 * the toggle.
 */
@Composable
fun NoteScreen(
    state: NoteUiState,
    editor: EditorState,
    previewListState: LazyListState,
    actions: NoteActions,
) {
    val focused by editor.focused.collectAsState()
    val pressed by editor.pressed.collectAsState()
    // Edit -> Preview: the source offset of the first visible line, until the Preview is parsed and scrolled to it.
    var scrollPreviewTo by remember { mutableStateOf<Int?>(null) }
    val doc = state.doc
    LaunchedEffect(doc, state.preview) {
        val offset = scrollPreviewTo
        if (state.preview && doc != null && offset != null) {
            previewListState.scrollToItem(doc.blockIndexAt(offset))
            scrollPreviewTo = null
        }
    }
    val toggle = {
        if (state.preview) {
            // Preview -> Edit: the editor scrolls to the source line of the first visible block.
            doc?.blocks?.getOrNull(previewListState.firstVisibleItemIndex)?.let { editor.scrollTo(it.start) }
        } else {
            scrollPreviewTo = editor.firstVisibleOffset()
        }
        actions.onTogglePreview()
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(CittiTheme.colors.bg)
            .safeDrawingPadding(),
    ) {
        NoteTopBar(state, showHistory = !state.preview && !state.loading && focused, actions = actions, onToggle = toggle)
        NoteBanner(state, actions)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading -> {
                    if (state.title.isNotEmpty() || state.offline) {
                        Gutter {
                            BasicText(
                                stringResource(if (state.offline) Res.string.note_not_downloaded else Res.string.note_downloading),
                                style = CittiTheme.typography.meta.colored(muted = true),
                            )
                        }
                    }
                }

                state.preview -> {
                    if (doc != null) {
                        MarkdownPreview(
                            doc = doc,
                            links = state.links,
                            listState = previewListState,
                            readonly = state.readonly,
                            onTask = actions.onTask,
                            onLink = actions.onLink,
                        )
                    }
                }

                else -> {
                    MarkdownEditor(editor, Modifier.fillMaxSize(), editable = !state.readonly)
                }
            }
        }
        if (!state.preview && !state.loading && focused) FormatToolbar(pressed, actions.onToolbar)
    }
}

@Composable
private fun NoteTopBar(
    state: NoteUiState,
    showHistory: Boolean,
    actions: NoteActions,
    onToggle: () -> Unit,
) {
    val colors = CittiTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = NotesSize.barHeight)
            .padding(end = NotesSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(CittiIcon.Back, stringResource(Res.string.action_back), actions.onBack)
        Column(Modifier.weight(1f)) {
            BasicText(
                state.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
                style = CittiTheme.typography.title.colored(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space2), verticalAlignment = Alignment.CenterVertically) {
                if (!state.loading) {
                    val (label, color) =
                        when (state.save) {
                            SaveIndicator.Saved -> stringResource(Res.string.save_saved) to colors.inkMuted
                            SaveIndicator.Pending -> stringResource(Res.string.save_pending) to colors.inkMuted
                            SaveIndicator.Syncing -> stringResource(Res.string.save_syncing) to colors.inkMuted
                            SaveIndicator.Offline -> stringResource(Res.string.save_offline) to colors.inkMuted
                            SaveIndicator.Failed -> stringResource(Res.string.save_failed) to colors.danger
                        }
                    BasicText(
                        label,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = CittiTheme.typography.meta.copy(color = color),
                    )
                }
                if (state.readonly) StateLabel(stringResource(Res.string.note_readonly), colors.inkMuted)
            }
        }
        if (showHistory) {
            IconButton(
                CittiIcon.Undo,
                stringResource(Res.string.note_undo),
                actions.onUndo,
                color = if (state.canUndo) colors.ink else colors.inkFaint,
            )
            IconButton(
                CittiIcon.Redo,
                stringResource(Res.string.note_redo),
                actions.onRedo,
                color = if (state.canRedo) colors.ink else colors.inkFaint,
            )
        }
        if (!state.readonly && !state.loading) {
            if (state.preview) {
                IconButton(CittiIcon.Pencil, stringResource(Res.string.note_edit), onToggle)
            } else {
                IconButton(CittiIcon.Eye, stringResource(Res.string.note_preview), onToggle)
            }
        }
    }
}

/** At most one: the Conflict banner, else the auth failure banner. */
@Composable
private fun NoteBanner(
    state: NoteUiState,
    actions: NoteActions,
) {
    val conflict = state.conflict
    when {
        conflict != null -> {
            val message =
                stringResource(if (conflict.isCopy) Res.string.conflict_copy_of else Res.string.conflict_banner, conflict.title)
            Banner(message, BannerKind.Offline, actionLabel = stringResource(Res.string.action_open), onAction = actions.onOpenConflict)
        }

        state.authFailed -> {
            Banner(
                stringResource(Res.string.banner_auth_failed),
                BannerKind.Error,
                actionLabel = stringResource(Res.string.banner_sign_in_again),
                onAction = actions.onSignInAgain,
            )
        }
    }
}
