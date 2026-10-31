package app.citti.e2e.flows

import android.app.NotificationManager
import app.citti.SyncNotification
import app.citti.core.sync.SyncTrigger
import app.citti.e2e.E2e
import app.citti.e2e.robots.ListRobot
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class SyncNotificationTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    private val notifications get() = E2e.app.getSystemService(NotificationManager::class.java)

    private fun syncNotificationIds() =
        notifications.activeNotifications
            .map { it.id }
            .filter { it == SyncNotification.SERVICE_ID || it == SyncNotification.WORKER_ID }

    @Test
    fun theSyncNotificationClearsAfterManyQuickSyncs() {
        ListRobot(e2e).assertRow("Roadmap")
        // Each one is a 304 of a few ms that starts and stops the foreground service: the case that left an
        // ongoing "Syncing" notification behind when the service posted after it had stopped.
        repeat(20) { runBlocking { E2e.container.sync(SyncTrigger.Manual) } }

        runCatching { e2e.compose.waitUntil(timeoutMillis = 5_000) { syncNotificationIds().isEmpty() } }
        assertEquals(emptyList(), syncNotificationIds(), "sync notifications left after the syncs")
    }
}
