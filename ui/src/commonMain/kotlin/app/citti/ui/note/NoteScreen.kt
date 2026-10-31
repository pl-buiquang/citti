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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.citti.core.markdown.Task
import app.citti.core.markdown.ToolbarAction
import app.citti.ui.TestTags
import app.citti.ui.components.Banner
import app.citti.ui.components.BannerKind
import app.citti.ui.components.CittiIcon
import app.citti.ui.components.Gutter
import app.citti.ui.components.IconButton
import app.citti.ui.components.ImageSourceSheet
import app.citti.ui.components.MenuItem
import app.citti.ui.components.NoteMenu
import app.citti.ui.components.PickerFolders
import app.citti.ui.components.StateLabel
import app.citti.ui.components.UploadChip
import app.citti.ui.components.colored
import app.citti.ui.daily.TemplateMissingBanner
import app.citti.ui.image.ImageEnv
import app.citti.ui.image.PickSource
import app.citti.ui.markdown.EditorState
import app.citti.ui.markdown.FormatToolbar
import app.citti.ui.markdown.MarkdownEditor
import app.citti.ui.markdown.MarkdownPreview
import app.citti.ui.nav.NoteNotice
import app.citti.ui.resources.Res
import app.citti.ui.resources.action_back
import app.citti.ui.resources.action_open
import app.citti.ui.resources.banner_auth_failed
import app.citti.ui.resources.banner_sign_in_again
import app.citti.ui.resources.conflict_banner
import app.citti.ui.resources.conflict_copy_of
import app.citti.ui.resources.note_downloading
import app.citti.ui.resources.note_edit
import app.citti.ui.resources.note_menu
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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
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
    /** A row of the Insert image sheet: open the platform picker for it. */
    val onPickImage: (PickSource) -> Unit = {},
    val onDismissImageSheet: () -> Unit = {},
    val onCancelUpload: () -> Unit = {},
    /** The ⋮ button, a menu item, a tap outside the menu. */
    val onOpenMenu: () -> Unit = {},
    val onMenuItem: (MenuItem) -> Unit = {},
    val onDismissMenu: () -> Unit = {},
    /** The ⋮ menu's dialogs; null: none is shown. */
    val dialogs: NoteDialogActions? = null,
    /** The Template missing banner's "Settings" and "Dismiss". */
    val onOpenSettings: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
    /** The source line at the top of the screen, once scrolling settles: the Reading position. */
    val onViewLine: (Int) -> Unit = {},
)

private const val VIEW_LINE_MS = 300L

/**
 * The Note screen ([design-brief] §5, §6): top bar (←, title and save indicator, ↶ ↷ while the editor has focus,
 * the Edit/Preview toggle, ⋮), at most one banner (Conflict, then auth failure, then Template missing), the body (the
 * editor in Edit mode, the Preview in Preview mode, "Downloading…" while the text is not on the device) and the format
 * toolbar above the keyboard while the editor has focus. [previewListState] is owned by the caller so the reading
 * position survives the toggle. [images] loads the Images of the Preview (null: a box with the file name).
 */
@OptIn(FlowPreview::class)
@Composable
fun NoteScreen(
    state: NoteUiState,
    editor: EditorState,
    previewListState: LazyListState,
    actions: NoteActions,
    images: ImageEnv? = null,
    /** The Folders the Move picker lists. */
    folders: Flow<PickerFolders>? = null,
    /** Note info's last modified (epoch seconds), as an absolute date and time. */
    dateTime: (Long) -> String = { it.toString() },
) {
    val focused by editor.focused.collectAsState()
    val pressed by editor.pressed.collectAsState()
    // Edit -> Preview: the source offset of the first visible line, until the Preview is parsed and scrolled to it.
    var scrollPreviewTo by remember { mutableStateOf<Int?>(null) }
    val doc = state.doc
    LaunchedEffect(doc, state.preview) {
        // Or the offset of Route.Note.startLine, when the Note was opened at a line.
        val offset = scrollPreviewTo ?: state.startOffset
        if (state.preview && doc != null && offset != null) {
            previewListState.scrollToItem(doc.blockIndexAt(offset))
            scrollPreviewTo = null
            actions.onViewLine(editor.document.lineOf(offset.coerceIn(0, editor.document.length)))
        }
    }
    // The Reading position. Edit mode has no scroll event in common code, so the top line is sampled.
    LaunchedEffect(state.preview, state.loading) {
        if (state.preview || state.loading) return@LaunchedEffect
        var last = -1
        while (true) {
            delay(VIEW_LINE_MS)
            val line = editor.document.lineOf(editor.firstVisibleOffset().coerceIn(0, editor.document.length))
            if (line != last) {
                last = line
                actions.onViewLine(line)
            }
        }
    }
    LaunchedEffect(state.preview, doc) {
        if (!state.preview || doc == null) return@LaunchedEffect
        snapshotFlow { previewListState.firstVisibleItemIndex }
            .debounce(VIEW_LINE_MS)
            .collect { index -> doc.blocks.getOrNull(index)?.let { actions.onViewLine(editor.document.lineOf(it.start)) } }
    }
    val toggle = {
        if (state.preview) {
            // Preview -> Edit: the editor scrolls to the source line of the first visible block.
            doc?.blocks?.getOrNull(previewListState.firstVisibleItemIndex)?.let {
                editor.scrollTo(it.start)
                actions.onViewLine(editor.document.lineOf(it.start))
            }
        } else {
            scrollPreviewTo = editor.firstVisibleOffset()
            actions.onViewLine(editor.document.lineOf(scrollPreviewTo!!.coerceIn(0, editor.document.length)))
        }
        actions.onTogglePreview()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(CittiTheme.colors.bg)
            .safeDrawingPadding(),
    ) {
        NoteColumn(state, editor, previewListState, actions, images, focused, pressed, toggle)
        if (state.menuOpen) NoteMenu(state.menu, state.favorite, actions.onMenuItem, actions.onDismissMenu)
        actions.dialogs?.let { NoteDialogHost(state.dialog, state.title, folders, dateTime, it) }
        if (state.imageSheet) ImageSourceSheet(onPick = actions.onPickImage, onDismiss = actions.onDismissImageSheet)
    }
}

@Composable
private fun NoteColumn(
    state: NoteUiState,
    editor: EditorState,
    previewListState: LazyListState,
    actions: NoteActions,
    images: ImageEnv?,
    focused: Boolean,
    pressed: Set<ToolbarAction>,
    toggle: () -> Unit,
) {
    val doc = state.doc
    Column(Modifier.fillMaxSize()) {
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
                            images = images,
                        )
                    }
                }

                else -> {
                    MarkdownEditor(editor, Modifier.fillMaxSize().testTag(TestTags.NOTE_EDITOR), editable = !state.readonly)
                }
            }
        }
        if (state.uploading) UploadChip(actions.onCancelUpload)
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
        if (state.menu.isNotEmpty()) {
            IconButton(
                CittiIcon.More,
                stringResource(Res.string.note_menu),
                actions.onOpenMenu,
                Modifier.testTag(TestTags.NOTE_MENU_BUTTON),
            )
        }
    }
}

/** At most one: the Conflict banner, else the auth failure banner, else the route's notice (Template missing). */
@Composable
private fun NoteBanner(
    state: NoteUiState,
    actions: NoteActions,
) {
    val conflict = state.conflict
    val notice = state.notice
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

        notice is NoteNotice.TemplateMissing -> {
            TemplateMissingBanner(notice.path, onSettings = actions.onOpenSettings, onDismiss = actions.onDismissNotice)
        }
    }
}
