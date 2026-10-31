package app.citti.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.citti.core.note.find
import app.citti.core.sync.SyncStatus
import app.citti.ui.components.Banner
import app.citti.ui.components.BannerKind
import app.citti.ui.components.Breadcrumb
import app.citti.ui.components.CittiIcon
import app.citti.ui.components.CittiPullRefresh
import app.citti.ui.components.CittiTopBar
import app.citti.ui.components.EmptyState
import app.citti.ui.components.FolderRow
import app.citti.ui.components.Gutter
import app.citti.ui.components.IconButton
import app.citti.ui.components.ModeSwitch
import app.citti.ui.components.NoteRow
import app.citti.ui.components.SearchBar
import app.citti.ui.components.SearchLeading
import app.citti.ui.components.colored
import app.citti.ui.resources.Res
import app.citti.ui.resources.action_back
import app.citti.ui.resources.action_details
import app.citti.ui.resources.action_hide_details
import app.citti.ui.resources.action_retry
import app.citti.ui.resources.banner_auth_failed
import app.citti.ui.resources.banner_failed
import app.citti.ui.resources.banner_first_sync
import app.citti.ui.resources.banner_offline
import app.citti.ui.resources.banner_sign_in_again
import app.citti.ui.resources.drawer_favorites
import app.citti.ui.resources.empty_favorites
import app.citti.ui.resources.empty_folder
import app.citti.ui.resources.empty_list
import app.citti.ui.resources.empty_list_first_sync
import app.citti.ui.resources.sync_server_error
import app.citti.ui.resources.sync_syncing
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSize
import app.citti.ui.theme.NotesSpacing
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** What the main view shows: List mode (optionally favorites only) or Tree mode at a Folder. */
sealed interface BrowseView {
    data class List(
        val favoritesOnly: Boolean,
    ) : BrowseView

    data class Tree(
        val folder: String,
    ) : BrowseView
}

/**
 * The user's actions on the main view. [onOpenFolder] opens a child Folder, [onOpenBreadcrumb] an ancestor.
 * [onOpenNote] null leaves the rows without a tap action.
 */
class BrowseActions(
    val onOpenMenu: () -> Unit,
    val onOpenSearch: () -> Unit,
    val onShowList: () -> Unit,
    val onShowTree: () -> Unit,
    val onOpenFolder: (path: String) -> Unit,
    val onOpenBreadcrumb: (path: String) -> Unit,
    val onOpenNote: ((localId: Long) -> Unit)?,
    val onBack: () -> Unit,
    val onSyncNow: () -> Unit,
    val onSignInAgain: () -> Unit,
)

/**
 * The main view (List and Tree mode). [view] comes from the route on top of the back stack; rows that
 * [state] still holds for another route are not shown. [nowSeconds] is the clock of the row dates and the
 * banner, [dates] the calendar behind them, [listState] the scroll position of this route entry.
 */
@Composable
fun BrowseScreen(
    view: BrowseView,
    state: BrowseUiState,
    nowSeconds: () -> Long,
    dates: RowDateFormatter,
    listState: LazyListState,
    actions: BrowseActions,
) {
    val now by produceState(nowSeconds()) {
        while (true) {
            delay(30_000)
            value = nowSeconds()
        }
    }
    val favoritesOnly = view is BrowseView.List && view.favoritesOnly
    Column(
        Modifier
            .fillMaxSize()
            .background(CittiTheme.colors.bg)
            .safeDrawingPadding(),
    ) {
        if (favoritesOnly) {
            CittiTopBar(stringResource(Res.string.drawer_favorites), onBack = actions.onBack)
        } else {
            SearchBar(
                query = "",
                onQueryChange = {},
                leading = SearchLeading.Menu,
                onLeading = actions.onOpenMenu,
                onOpen = actions.onOpenSearch,
                modifier = Modifier.padding(vertical = NotesSpacing.space2),
            )
        }
        // The right side stays free for the Calendar button (M6).
        Row(Modifier.fillMaxWidth().padding(horizontal = CittiTheme.shape.gutter, vertical = NotesSpacing.space1)) {
            ModeSwitch(tree = view is BrowseView.Tree, onSelectList = actions.onShowList, onSelectTree = actions.onShowTree)
        }
        BannerSlot(state.banner, now, actions)
        if (view is BrowseView.Tree) {
            Breadcrumb(view.folder, onOpen = actions.onOpenBreadcrumb, modifier = Modifier.padding(vertical = NotesSpacing.space1))
        }
        CittiPullRefresh(refreshing = state.syncing, onRefresh = actions.onSyncNow, modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                when (view) {
                    is BrowseView.List -> listRows(view, state, now, dates, actions)
                    is BrowseView.Tree -> treeRows(view, state, now, dates, actions)
                }
            }
        }
    }
}

private fun LazyListScope.listRows(
    view: BrowseView.List,
    state: BrowseUiState,
    now: Long,
    dates: RowDateFormatter,
    actions: BrowseActions,
) {
    if (!state.loaded || state.favoritesOnly != view.favoritesOnly) return
    if (state.list.isEmpty()) {
        item(key = "empty") {
            val message =
                when {
                    view.favoritesOnly -> Res.string.empty_favorites
                    state.firstSync -> Res.string.empty_list_first_sync
                    else -> Res.string.empty_list
                }
            EmptyState(stringResource(message), Modifier.fillParentMaxSize())
        }
        return
    }
    items(state.list, key = { it.localId }) { item ->
        NoteItemRow(item, folder = item.folder, now, dates, actions.onOpenNote)
    }
}

private fun LazyListScope.treeRows(
    view: BrowseView.Tree,
    state: BrowseUiState,
    now: Long,
    dates: RowDateFormatter,
    actions: BrowseActions,
) {
    // Wait for the tree and for the Notes of this Folder, not those of the previous route.
    val tree = state.tree ?: return
    if (state.folder != view.folder) return
    val folders = tree.find(view.folder)?.children.orEmpty()
    if (folders.isEmpty() && state.folderNotes.isEmpty()) {
        item(key = "empty") {
            val message =
                when {
                    view.folder.isNotEmpty() -> Res.string.empty_folder
                    state.firstSync -> Res.string.empty_list_first_sync
                    else -> Res.string.empty_list
                }
            EmptyState(stringResource(message), Modifier.fillParentMaxSize())
        }
        return
    }
    items(folders, key = { "f:${it.path}" }) { folder ->
        FolderRow(folder.name, folder.totalNotes, onClick = { actions.onOpenFolder(folder.path) })
    }
    // The Breadcrumb already names the Folder, so the rows show only the Excerpt.
    items(state.folderNotes, key = { "n:${it.localId}" }) { item ->
        NoteItemRow(item, folder = null, now, dates, actions.onOpenNote)
    }
}

@Composable
private fun NoteItemRow(
    item: NoteRowItem,
    folder: String?,
    now: Long,
    dates: RowDateFormatter,
    onOpenNote: ((Long) -> Unit)?,
) {
    NoteRow(
        title = item.title,
        folder = folder,
        excerpt = item.excerpt,
        date = rowDateText(item.modified, now, dates),
        favorite = item.favorite,
        badge = item.badge,
        onClick = onOpenNote?.let { open -> { open(item.localId) } },
    )
}

/** At most one banner (§8.6 of the M3 spec); see [BrowseUiState.banner]. */
@Composable
private fun BannerSlot(
    banner: BrowseBanner?,
    now: Long,
    actions: BrowseActions,
) {
    when (banner) {
        null -> {
            Unit
        }

        BrowseBanner.AuthFailed -> {
            Banner(
                stringResource(Res.string.banner_auth_failed),
                BannerKind.Error,
                actionLabel = stringResource(Res.string.banner_sign_in_again),
                onAction = actions.onSignInAgain,
            )
        }

        is BrowseBanner.FirstSync -> {
            val progress = banner.progress
            val message =
                if (progress == null || progress.total == 0) {
                    stringResource(Res.string.sync_syncing)
                } else {
                    stringResource(Res.string.banner_first_sync, formatCount(progress.done), formatCount(progress.total))
                }
            Banner(message, BannerKind.Offline)
        }

        is BrowseBanner.Offline -> {
            val offline = stringResource(Res.string.banner_offline)
            val age =
                banner.lastSuccessAt?.let { at ->
                    (syncLine(SyncStatus.Idle(at, null), now) as? SyncLine.Synced)?.let { syncedText(it) }
                }
            Banner(if (age == null) offline else "$offline · $age", BannerKind.Offline)
        }

        is BrowseBanner.Failed -> {
            FailedBanner(banner, actions.onSyncNow)
        }
    }
}

@Composable
private fun FailedBanner(
    banner: BrowseBanner.Failed,
    onRetry: () -> Unit,
) {
    var expanded by remember(banner) { mutableStateOf(false) }
    val detail = banner.status?.let { stringResource(Res.string.sync_server_error, it) } ?: banner.message
    Column(Modifier.fillMaxWidth().background(CittiTheme.colors.surface)) {
        Banner(
            stringResource(Res.string.banner_failed),
            BannerKind.Error,
            actionLabel = stringResource(Res.string.action_retry),
            onAction = onRetry,
            secondaryLabel = detail?.let { stringResource(if (expanded) Res.string.action_hide_details else Res.string.action_details) },
            onSecondary = { expanded = !expanded },
        )
        if (expanded && detail != null) {
            Gutter {
                BasicText(
                    detail,
                    modifier = Modifier.padding(bottom = NotesSpacing.space2),
                    style = CittiTheme.typography.meta.colored(muted = true),
                )
            }
        }
    }
}
