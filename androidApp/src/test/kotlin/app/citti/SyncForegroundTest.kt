package app.citti

import app.citti.core.sync.Progress
import app.citti.core.sync.SyncPhase
import app.citti.core.sync.SyncStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncForegroundTest {
    @Test
    fun notificationShowsProgressWhileDownloading() {
        val status = SyncStatus.Running(SyncPhase.Pulling, Progress(120, 2300), null)
        assertEquals(SyncNotificationContent("Syncing", "120 / 2300", 120, 2300), notificationContent(status))
    }

    @Test
    fun notificationIsIndeterminateWithoutProgress() {
        val expected = SyncNotificationContent("Syncing", null, 0, 0)
        assertEquals(expected, notificationContent(null))
        assertEquals(expected, notificationContent(SyncStatus.Running(SyncPhase.Pushing, null, null)))
        assertEquals(expected, notificationContent(SyncStatus.Running(SyncPhase.Pulling, Progress(0, 0), null)))
    }

    @Test
    fun notificationIsPostedOnlyWhileForegroundAndRunning() {
        val running = SyncStatus.Running(SyncPhase.Pulling, Progress(1, 2), null)
        assertTrue(shouldPostNotification(running, foreground = true))
        assertFalse(shouldPostNotification(running, foreground = false))
        assertFalse(shouldPostNotification(SyncStatus.Idle(null, null), foreground = true))
        assertFalse(shouldPostNotification(SyncStatus.Offline(null), foreground = true))
        assertFalse(shouldPostNotification(null, foreground = true))
    }

    @Test
    fun notificationPermissionIsAskedOnceFromAndroid13() {
        assertTrue(shouldAskNotificationPermission(sdk = 33, granted = false, alreadyAsked = false))
        assertTrue(shouldAskNotificationPermission(sdk = 36, granted = false, alreadyAsked = false))
        assertFalse(shouldAskNotificationPermission(sdk = 32, granted = false, alreadyAsked = false))
        assertFalse(shouldAskNotificationPermission(sdk = 34, granted = true, alreadyAsked = false))
        assertFalse(shouldAskNotificationPermission(sdk = 34, granted = false, alreadyAsked = true))
    }
}
