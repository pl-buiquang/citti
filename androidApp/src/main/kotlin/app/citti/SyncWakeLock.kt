package app.citti

import android.content.Context
import android.os.PowerManager

/**
 * Runs [block] holding a partial wake lock so the CPU keeps running while the screen is off. Released in
 * `finally`; the timeout is only a safety net if the process is stuck. Held for the sync only, never longer.
 */
suspend fun <T> withSyncWakeLock(
    context: Context,
    block: suspend () -> T,
): T {
    val power = context.getSystemService(PowerManager::class.java)
    val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "citti:sync").apply { setReferenceCounted(false) }
    lock.acquire(WAKE_LOCK_TIMEOUT_MS)
    try {
        return block()
    } finally {
        if (lock.isHeld) lock.release()
    }
}

private const val WAKE_LOCK_TIMEOUT_MS = 20 * 60 * 1000L
