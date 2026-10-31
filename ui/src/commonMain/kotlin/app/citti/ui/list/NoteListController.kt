package app.citti.ui.list

import app.citti.core.db.Note
import app.citti.core.note.noteBadgeOf
import app.citti.core.sync.Progress
import app.citti.core.sync.SyncStatus
import app.citti.core.sync.SyncTrigger
import app.citti.ui.components.RowBadge
import app.citti.ui.components.toRowBadge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row of the debug list. [folder] is empty for a Note at the Vault root. */
data class NoteListItem(
    val localId: Long,
    val title: String,
    val folder: String,
    val favorite: Boolean,
    val badge: RowBadge?,
)

/** A Conflict copy shows "Conflict"; any other unsynced Note shows "Pending". */
fun Note.toListItem(): NoteListItem =
    NoteListItem(
        localId = local_id,
        title = title,
        folder = category,
        favorite = favorite,
        badge = noteBadgeOf(conflict_of, sync_state)?.toRowBadge(),
    )

data class NoteListUiState(
    val items: List<NoteListItem> = emptyList(),
    /** Null until the sync stack exists. */
    val status: SyncStatus? = null,
    val loaded: Boolean = false,
) {
    val syncing: Boolean get() = status is SyncStatus.Running
}

/**
 * State holder of the debug list. [notes] and [status] come from the local DB and the SyncEngine;
 * [sync] runs a sync with the given trigger (the app resolves the engine, so it may be a no-op signed out).
 */
class NoteListController(
    private val scope: CoroutineScope,
    notes: Flow<List<NoteListItem>>,
    status: Flow<SyncStatus?>,
    private val sync: suspend (SyncTrigger) -> Unit,
) {
    val state: StateFlow<NoteListUiState> =
        combine(notes, status) { items, st -> NoteListUiState(items, st, loaded = true) }
            .stateIn(scope, SharingStarted.Eagerly, NoteListUiState())

    private var opened = false

    /** Call when the list appears; syncs once per controller, however often the screen is recomposed. */
    fun onShown() {
        if (opened) return
        opened = true
        run(SyncTrigger.AppOpen)
    }

    fun syncNow() = run(SyncTrigger.Manual)

    private fun run(trigger: SyncTrigger) {
        scope.launch { sync(trigger) }
    }
}

enum class Age { JustNow, Minutes, Hours, Days }

/** What the status line says, before it is turned into text. */
sealed interface SyncLine {
    data class Syncing(
        val progress: Progress?,
    ) : SyncLine

    data object NeverSynced : SyncLine

    data class Synced(
        val age: Age,
        val amount: Int,
    ) : SyncLine

    data object Offline : SyncLine

    data object AuthFailed : SyncLine

    data class ServerError(
        val status: Int,
    ) : SyncLine

    data object Failed : SyncLine
}

/** [now] and `lastSuccessAt` are epoch seconds. */
fun syncLine(
    status: SyncStatus?,
    now: Long,
): SyncLine =
    when (status) {
        null -> SyncLine.NeverSynced
        is SyncStatus.Running -> SyncLine.Syncing(status.progress)
        is SyncStatus.Idle -> status.lastSuccessAt?.let { ageOf(now - it) } ?: SyncLine.NeverSynced
        is SyncStatus.Offline -> SyncLine.Offline
        is SyncStatus.AuthFailed -> SyncLine.AuthFailed
        is SyncStatus.ServerError -> SyncLine.ServerError(status.status)
        is SyncStatus.Failed -> SyncLine.Failed
    }

private fun ageOf(seconds: Long): SyncLine.Synced =
    when {
        seconds < 60 -> SyncLine.Synced(Age.JustNow, 0)
        seconds < 3600 -> SyncLine.Synced(Age.Minutes, (seconds / 60).toInt())
        seconds < 86_400 -> SyncLine.Synced(Age.Hours, (seconds / 3600).toInt())
        else -> SyncLine.Synced(Age.Days, (seconds / 86_400).toInt())
    }

/** 1240 becomes "1,240". Locale-free on purpose (commonMain). */
fun formatCount(n: Int): String {
    val digits = n.toString().removePrefix("-")
    val grouped =
        digits
            .reversed()
            .chunked(3)
            .joinToString(",")
            .reversed()
    return if (n < 0) "-$grouped" else grouped
}
