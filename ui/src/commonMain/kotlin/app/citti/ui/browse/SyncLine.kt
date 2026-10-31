package app.citti.ui.browse

import app.citti.core.sync.Progress
import app.citti.core.sync.SyncStatus

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
