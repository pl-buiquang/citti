package app.citti.core.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pushes shortly after an edit: [schedule] restarts a timer of [delayMillis]; when it fires, the engine pushes the
 * queue ([SyncEngine.push], no list request). Only the timer restarts: a push already running is never cancelled by
 * the next keystroke, the engine runs one more push after it when something is still pending.
 * Offline or failed pushes are not retried here; the periodic job and the next app open pick the change up.
 */
class AfterEditPush(
    private val scope: CoroutineScope,
    private val engine: SyncEngine,
    private val delayMillis: Long = DEFAULT_DELAY,
) {
    private var timer: Job? = null

    fun schedule() {
        timer?.cancel()
        timer =
            scope.launch {
                delay(delayMillis)
                // A separate job, so that a later schedule() cancels the timer but not this push.
                scope.launch { engine.push(SyncTrigger.AfterEdit) }
            }
    }

    /** Drops a pending timer, e.g. at sign-out. */
    fun cancel() {
        timer?.cancel()
        timer = null
    }

    companion object {
        const val DEFAULT_DELAY = 2_000L
    }
}
