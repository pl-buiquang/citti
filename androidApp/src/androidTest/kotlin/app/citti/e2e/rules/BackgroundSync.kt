package app.citti.e2e.rules

import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.citti.SyncScheduler
import app.citti.core.fake.Endpoint
import app.citti.e2e.E2e
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Fires the periodic [app.citti.SyncWorker] the app scheduled, as if its period had elapsed. */
object BackgroundSync {
    /** Runs the worker once and returns after it finished. The sync it runs must send a `LIST`. */
    fun runPeriodic(timeout: Duration = 10.seconds) {
        val workManager = WorkManager.getInstance(E2e.app)
        val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(E2e.app)) { "test WorkManager not initialized" }
        val infos = workManager.getWorkInfosForUniqueWorkFlow(SyncScheduler.UNIQUE_NAME).mapNotNull { it.singleOrNull() }
        runBlocking {
            withTimeout(timeout) {
                val work = infos.first { it.state == WorkInfo.State.ENQUEUED }
                val lists = E2e.server.count(Endpoint.LIST)
                driver.setAllConstraintsMet(work.id)
                driver.setPeriodDelayMet(work.id)
                // A periodic work goes back to ENQUEUED once it ran; by then its sync has listed the Notes.
                infos.first { it.state == WorkInfo.State.ENQUEUED && E2e.server.count(Endpoint.LIST) > lists }
            }
        }
    }
}
