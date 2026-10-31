package app.citti.ui.browse

import androidx.compose.runtime.Composable
import app.citti.core.sync.Progress
import app.citti.core.sync.SyncStatus
import app.citti.ui.resources.Res
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
import org.jetbrains.compose.resources.stringResource

/** The sync line as text ("Synced 2 min ago · 3 pending"), for Settings and the drawer. */
@Composable
internal fun statusText(
    line: SyncLine,
    status: SyncStatus?,
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
    val pending = status?.pendingChanges ?: 0
    val conflicts = status?.conflictCopies ?: 0
    return buildList {
        add(main)
        if (pending > 0) add(stringResource(Res.string.sync_pending_count, pending))
        if (conflicts > 0) add(stringResource(Res.string.sync_conflict_count, conflicts))
    }.joinToString(" · ")
}

@Composable
internal fun syncingText(progress: Progress?): String =
    if (progress == null || progress.total == 0) {
        stringResource(Res.string.sync_syncing)
    } else {
        stringResource(Res.string.sync_syncing_progress, formatCount(progress.done), formatCount(progress.total))
    }

@Composable
internal fun syncedText(line: SyncLine.Synced): String =
    when (line.age) {
        Age.JustNow -> stringResource(Res.string.sync_synced_now)
        Age.Minutes -> stringResource(Res.string.sync_synced_minutes, line.amount)
        Age.Hours -> stringResource(Res.string.sync_synced_hours, line.amount)
        Age.Days -> stringResource(Res.string.sync_synced_days, line.amount)
    }
