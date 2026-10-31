package app.citti

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import app.citti.core.sync.SyncStatus

/** What the ongoing sync notification shows. [total] is 0 when there is no progress to show. */
data class SyncNotificationContent(
    val title: String,
    val text: String?,
    val done: Int,
    val total: Int,
)

/** Pure mapping from the engine state: "Syncing" and, during the content download, "n / total". */
fun notificationContent(status: SyncStatus?): SyncNotificationContent {
    val progress = (status as? SyncStatus.Running)?.progress
    return if (progress != null && progress.total > 0) {
        SyncNotificationContent("Syncing", "${progress.done} / ${progress.total}", progress.done, progress.total)
    } else {
        SyncNotificationContent("Syncing", null, 0, 0)
    }
}

/**
 * Whether the service should update its notification: only while it is in the foreground, and only with
 * progress to show. A post after `stopForeground` would leave an ongoing notification behind.
 */
fun shouldPostNotification(
    status: SyncStatus?,
    foreground: Boolean,
): Boolean = foreground && status is SyncStatus.Running

/**
 * The low-importance ongoing notification of [SyncForegroundService] and [SyncWorker]. Each has its own id:
 * two foreground services sharing one notification remove it at the wrong time.
 */
object SyncNotification {
    const val CHANNEL_ID = "sync"
    const val SERVICE_ID = 1001
    const val WORKER_ID = 1002

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Sync", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    fun build(
        context: Context,
        content: SyncNotificationContent = notificationContent(null),
    ): Notification {
        ensureChannel(context)
        return NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // A bar with a known total, otherwise an indeterminate one.
            .setProgress(content.total, content.done, content.total == 0)
            .build()
    }
}
