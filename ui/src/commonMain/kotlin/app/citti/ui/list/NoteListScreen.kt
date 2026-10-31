package app.citti.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import app.citti.core.sync.Progress
import app.citti.ui.components.Banner
import app.citti.ui.components.BannerKind
import app.citti.ui.components.ButtonKind
import app.citti.ui.components.CittiButton
import app.citti.ui.components.CittiTopBar
import app.citti.ui.components.Gutter
import app.citti.ui.components.NoteRow
import app.citti.ui.components.colored
import app.citti.ui.resources.Res
import app.citti.ui.resources.banner_auth_failed
import app.citti.ui.resources.banner_error
import app.citti.ui.resources.banner_offline
import app.citti.ui.resources.banner_retry
import app.citti.ui.resources.banner_sign_in_again
import app.citti.ui.resources.list_empty
import app.citti.ui.resources.list_empty_syncing
import app.citti.ui.resources.list_no_folder
import app.citti.ui.resources.list_settings
import app.citti.ui.resources.list_sync_now
import app.citti.ui.resources.list_title
import app.citti.ui.resources.sync_auth_failed
import app.citti.ui.resources.sync_conflict_count
import app.citti.ui.resources.sync_failed
import app.citti.ui.resources.sync_never
import app.citti.ui.resources.sync_offline
import app.citti.ui.resources.sync_pending_count
import app.citti.ui.resources.sync_server_error
import app.citti.ui.resources.sync_synced_days
import app.citti.ui.resources.sync_synced_hours
import app.citti.ui.resources.sync_synced_minutes
import app.citti.ui.resources.sync_synced_now
import app.citti.ui.resources.sync_syncing
import app.citti.ui.resources.sync_syncing_progress
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSpacing
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * Bare debug list (M3 replaces it). [nowSeconds] is the clock for "Synced 2 min ago"; it is passed in
 * so the screen stays free of platform time. [onOpenSettings] is the hook W8b wires to Settings.
 */
@Composable
fun NoteListScreen(
    state: NoteListUiState,
    nowSeconds: () -> Long,
    onSyncNow: () -> Unit,
    onSignInAgain: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val now by produceState(nowSeconds()) {
        while (true) {
            delay(30_000)
            value = nowSeconds()
        }
    }
    val line = syncLine(state.status, now)
    Column(
        Modifier
            .fillMaxSize()
            .background(CittiTheme.colors.bg)
            .safeDrawingPadding(),
    ) {
        CittiTopBar(
            title = stringResource(Res.string.list_title),
            actionLabel = stringResource(Res.string.list_settings),
            onAction = onOpenSettings,
        )
        StatusBanner(line, onSyncNow, onSignInAgain)
        Gutter {
            Column(Modifier.padding(vertical = NotesSpacing.space2), verticalArrangement = Arrangement.spacedBy(NotesSpacing.space2)) {
                BasicText(statusText(line, state), style = CittiTheme.typography.meta.colored(muted = true))
                CittiButton(
                    stringResource(Res.string.list_sync_now),
                    onSyncNow,
                    kind = ButtonKind.Quiet,
                    enabled = !state.syncing,
                )
            }
        }
        if (state.items.isEmpty() && state.loaded) {
            Gutter {
                BasicText(
                    stringResource(if (state.syncing) Res.string.list_empty_syncing else Res.string.list_empty),
                    modifier = Modifier.fillMaxWidth().padding(vertical = NotesSpacing.space6),
                    style = CittiTheme.typography.body.colored(muted = true),
                )
            }
        }
        val noFolder = stringResource(Res.string.list_no_folder)
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.items, key = { it.localId }) { item ->
                NoteRow(
                    title = item.title,
                    folder = item.folder.ifEmpty { noFolder },
                    favorite = item.favorite,
                    badge = item.badge,
                )
            }
        }
    }
}

@Composable
private fun StatusBanner(
    line: SyncLine,
    onRetry: () -> Unit,
    onSignInAgain: () -> Unit,
) {
    when (line) {
        SyncLine.Offline -> {
            Banner(stringResource(Res.string.banner_offline), BannerKind.Offline)
        }

        SyncLine.AuthFailed -> {
            Banner(
                stringResource(Res.string.banner_auth_failed),
                BannerKind.Error,
                actionLabel = stringResource(Res.string.banner_sign_in_again),
                onAction = onSignInAgain,
            )
        }

        is SyncLine.ServerError, SyncLine.Failed -> {
            Banner(
                stringResource(Res.string.banner_error),
                BannerKind.Error,
                actionLabel = stringResource(Res.string.banner_retry),
                onAction = onRetry,
            )
        }

        else -> {
            Unit
        }
    }
}

@Composable
private fun statusText(
    line: SyncLine,
    state: NoteListUiState,
): String {
    val main =
        when (line) {
            is SyncLine.Syncing -> syncingText(line.progress)
            SyncLine.NeverSynced -> stringResource(Res.string.sync_never)
            is SyncLine.Synced -> syncedText(line)
            SyncLine.Offline -> stringResource(Res.string.sync_offline)
            SyncLine.AuthFailed -> stringResource(Res.string.sync_auth_failed)
            is SyncLine.ServerError -> stringResource(Res.string.sync_server_error, line.status)
            SyncLine.Failed -> stringResource(Res.string.sync_failed)
        }
    val pending = state.status?.pendingChanges ?: 0
    val conflicts = state.status?.conflictCopies ?: 0
    return buildList {
        add(main)
        if (pending > 0) add(stringResource(Res.string.sync_pending_count, pending))
        if (conflicts > 0) add(stringResource(Res.string.sync_conflict_count, conflicts))
    }.joinToString(" · ")
}

@Composable
private fun syncingText(progress: Progress?): String =
    if (progress == null || progress.total == 0) {
        stringResource(Res.string.sync_syncing)
    } else {
        stringResource(Res.string.sync_syncing_progress, formatCount(progress.done), formatCount(progress.total))
    }

@Composable
private fun syncedText(line: SyncLine.Synced): String =
    when (line.age) {
        Age.JustNow -> stringResource(Res.string.sync_synced_now)
        Age.Minutes -> stringResource(Res.string.sync_synced_minutes, line.amount)
        Age.Hours -> stringResource(Res.string.sync_synced_hours, line.amount)
        Age.Days -> stringResource(Res.string.sync_synced_days, line.amount)
    }
