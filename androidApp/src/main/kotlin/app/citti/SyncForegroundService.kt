package app.citti

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and shows an ongoing "Syncing n / total" notification while
 * [AppContainer.runSync] runs. Android blocks the network of a backgrounded app when the screen goes off;
 * a foreground service (type `dataSync`) is what exempts the sync from that.
 *
 * Started only by [AppContainer]; it holds no sync logic of its own. It stops itself once the sync is over
 * ([AppContainer.foregroundSync]), and only after `startForeground`: a service stopped before that call crashes
 * the app (`ForegroundServiceDidNotStartInTimeException`), which a sync of a few ms (a 304) would otherwise do.
 */
class SyncForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    private val container get() = (application as CittiApp).container

    private val manager get() = getSystemService(NotificationManager::class.java)

    /** True between `startForeground` and `stopForeground`; the collector posts only then. */
    private var foreground = false

    private var collector: Job? = null

    // Not sticky: if the process dies mid-sync, the next trigger starts a fresh sync.
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // Every startForegroundService() needs its startForeground(), a running instance included.
        startForeground(
            SyncNotification.SERVICE_ID,
            SyncNotification.build(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        foreground = true
        if (collector?.isActive != true) collector = scope.launch { postProgress() }
        // stopSelf(startId) is ignored when a later start came in; that start's own wait stops the service.
        scope.launch {
            container.foregroundSync.first { !it }
            // Stop posting before the notification goes: a post after it is removed leaves an ongoing
            // notification that no service owns, and nothing clears it.
            foreground = false
            collector?.cancel()
            collector = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private suspend fun postProgress() {
        container.syncStatus.collect { status ->
            if (shouldPostNotification(status, foreground)) {
                manager.notify(SyncNotification.SERVICE_ID, SyncNotification.build(this, notificationContent(status)))
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        // Safety net for an orphan. Android ignores this for a notification a live foreground service holds.
        manager.cancel(SyncNotification.SERVICE_ID)
        super.onDestroy()
    }

    companion object {
        /** Returns false when Android refuses to start it; the sync then runs without the protection. It stops itself. */
        fun start(context: Context): Boolean =
            try {
                context.startForegroundService(Intent(context, SyncForegroundService::class.java))
                true
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException extends IllegalStateException.
                AndroidSyncLog.warn("foreground service not started: ${e.javaClass.simpleName}")
                false
            }
    }
}
