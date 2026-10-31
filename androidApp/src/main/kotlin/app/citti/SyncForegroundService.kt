package app.citti

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and shows an ongoing "Syncing n / total" notification while
 * [AppContainer.runSync] runs. Android blocks the network of a backgrounded app when the screen goes off;
 * a foreground service (type `dataSync`) is what exempts the sync from that.
 *
 * Started and stopped only by [AppContainer]; it holds no sync logic of its own.
 */
class SyncForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Done in onCreate so a stop right after the start still satisfies startForegroundService().
        startForeground(
            SyncNotification.ID,
            SyncNotification.build(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        val manager = getSystemService(NotificationManager::class.java)
        val container = (application as CittiApp).container
        scope.launch {
            container.syncStatus.collect { status ->
                manager.notify(SyncNotification.ID, SyncNotification.build(this@SyncForegroundService, notificationContent(status)))
            }
        }
    }

    // Not sticky: if the process dies mid-sync, the next trigger starts a fresh sync.
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Returns false when Android refuses to start it; the sync then runs without the protection. */
        fun start(context: Context): Boolean =
            try {
                context.startForegroundService(Intent(context, SyncForegroundService::class.java))
                true
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException extends IllegalStateException.
                AndroidSyncLog.warn("foreground service not started: ${e.javaClass.simpleName}")
                false
            }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncForegroundService::class.java))
        }
    }
}
