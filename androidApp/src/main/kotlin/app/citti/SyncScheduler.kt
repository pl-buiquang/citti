package app.citti

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.citti.core.db.CittiDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** What [SyncScheduler] enqueues for a [ScheduleConfig]; a null plan means no periodic work. */
data class PeriodicPlan(
    val intervalMinutes: Int,
    val networkType: NetworkType,
)

fun planFor(config: ScheduleConfig): PeriodicPlan? =
    if (config.isManualOnly) {
        null
    } else {
        PeriodicPlan(
            config.intervalMinutes,
            when (config.network) {
                SyncNetwork.ANY -> NetworkType.CONNECTED
                SyncNetwork.UNMETERED -> NetworkType.UNMETERED
            },
        )
    }

/**
 * Keeps the unique periodic sync work in line with the sync settings. Call [apply] after sign-in,
 * at startup when signed in, and whenever Settings changes the interval or network; call [cancel]
 * on sign-out.
 */
class SyncScheduler(
    context: Context,
    private val database: CittiDatabase,
) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    suspend fun apply() {
        val plan = planFor(readConfig())
        if (plan == null) {
            cancel()
            return
        }
        val request =
            PeriodicWorkRequestBuilder<SyncWorker>(plan.intervalMinutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(plan.networkType).build())
                .build()
        // UPDATE keeps the current period timing and swaps in the new interval and constraints.
        workManager.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel() {
        workManager.cancelUniqueWork(UNIQUE_NAME)
    }

    private suspend fun readConfig(): ScheduleConfig =
        withContext(Dispatchers.IO) {
            val q = database.settingQueries
            scheduleFrom(
                q.selectSetting(SyncScheduleKeys.INTERVAL_MINUTES).executeAsOneOrNull(),
                q.selectSetting(SyncScheduleKeys.NETWORK).executeAsOneOrNull(),
            )
        }

    companion object {
        const val UNIQUE_NAME = "citti-periodic-sync"
    }
}
