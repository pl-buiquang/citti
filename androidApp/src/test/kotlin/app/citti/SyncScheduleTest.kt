package app.citti

import androidx.work.NetworkType
import app.citti.core.api.ApiError
import app.citti.core.sync.SyncOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncScheduleTest {
    @Test
    fun defaultsWhenMissing() {
        assertEquals(ScheduleConfig(30, SyncNetwork.ANY), scheduleFrom(null, null))
    }

    @Test
    fun parsesAllowedValues() {
        for (m in listOf(15, 30, 60, 120, 360)) {
            assertEquals(m, scheduleFrom(m.toString(), null).intervalMinutes)
        }
        assertEquals(SyncNetwork.UNMETERED, scheduleFrom(null, "UNMETERED").network)
    }

    @Test
    fun unknownValuesFallBackToDefaults() {
        assertEquals(ScheduleConfig(30, SyncNetwork.ANY), scheduleFrom("7", "WIFI"))
        assertEquals(ScheduleConfig(30, SyncNetwork.ANY), scheduleFrom("abc", ""))
        assertEquals(30, scheduleFrom("-15", null).intervalMinutes)
    }

    @Test
    fun zeroMeansManualOnly() {
        val config = scheduleFrom("0", "UNMETERED")
        assertTrue(config.isManualOnly)
        assertNull(planFor(config))
    }

    @Test
    fun planMapsNetworkType() {
        assertEquals(PeriodicPlan(60, NetworkType.CONNECTED), planFor(scheduleFrom("60", "ANY")))
        assertEquals(PeriodicPlan(15, NetworkType.UNMETERED), planFor(scheduleFrom("15", "UNMETERED")))
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
