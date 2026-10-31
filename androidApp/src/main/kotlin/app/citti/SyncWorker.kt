package app.citti

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.citti.core.api.ApiError
import app.citti.core.sync.SyncOutcome
import app.citti.core.sync.SyncTrigger

enum class WorkDecision { Success, Retry, Failure }

/** Retries stop after this many attempts; the next period starts fresh. */
const val MAX_ATTEMPTS = 3

/** Pure mapping from a sync outcome to the worker result. A null [outcome] means signed out. */
fun decide(
    outcome: SyncOutcome?,
    runAttemptCount: Int,
): WorkDecision =
    when (outcome) {
        null, SyncOutcome.AlreadyRunning, SyncOutcome.NothingChanged, is SyncOutcome.Synced -> {
            WorkDecision.Success
        }

        is SyncOutcome.Failed -> {
            when (outcome.error) {
                is ApiError.Auth -> WorkDecision.Failure
                else -> if (runAttemptCount + 1 < MAX_ATTEMPTS) WorkDecision.Retry else WorkDecision.Failure
            }
        }
    }

/** Periodic background sync; does nothing when signed out. */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as CittiApp).container
        val outcome = container.syncEngine()?.sync(SyncTrigger.Periodic)
        return when (decide(outcome, runAttemptCount)) {
            WorkDecision.Success -> Result.success()
            WorkDecision.Retry -> Result.retry()
            WorkDecision.Failure -> Result.failure()
        }
    }
}
