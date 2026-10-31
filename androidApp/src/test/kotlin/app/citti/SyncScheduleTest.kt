package app.citti

import androidx.work.NetworkType
import app.citti.core.api.ApiError
import app.citti.core.settings.SyncNetwork
import app.citti.core.sync.SyncOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncScheduleTest {
    @Test
    fun zeroMeansManualOnly() {
        val config = ScheduleConfig(0, SyncNetwork.UNMETERED)
        assertTrue(config.isManualOnly)
        assertNull(planFor(config))
    }

    @Test
    fun planMapsNetworkType() {
        assertEquals(PeriodicPlan(60, NetworkType.CONNECTED), planFor(ScheduleConfig(60, SyncNetwork.ANY)))
        assertEquals(PeriodicPlan(15, NetworkType.UNMETERED), planFor(ScheduleConfig(15, SyncNetwork.UNMETERED)))
    }

    @Test
    fun workerDecisions() {
        assertEquals(WorkDecision.Success, decide(null, 0))
        assertEquals(WorkDecision.Success, decide(SyncOutcome.AlreadyRunning, 0))
        assertEquals(WorkDecision.Success, decide(SyncOutcome.NothingChanged, 0))
        assertEquals(WorkDecision.Success, decide(SyncOutcome.Synced(1, 1, 0, emptyList()), 0))
        assertEquals(WorkDecision.Failure, decide(SyncOutcome.Failed(ApiError.Auth(401)), 0))
        assertEquals(WorkDecision.Retry, decide(SyncOutcome.Failed(ApiError.Server(503)), 0))
        assertEquals(WorkDecision.Retry, decide(SyncOutcome.Failed(ApiError.Server(503)), 1))
        assertEquals(WorkDecision.Failure, decide(SyncOutcome.Failed(ApiError.Server(503)), 2))
    }
}
