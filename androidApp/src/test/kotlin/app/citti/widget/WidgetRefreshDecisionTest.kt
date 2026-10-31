package app.citti.widget

import app.citti.core.api.ApiError
import app.citti.core.sync.Dropped
import app.citti.core.sync.SyncOutcome
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidgetRefreshDecisionTest {
    @Test
    fun aSyncThatChangedNothingRedrawsNothing() {
        assertFalse(shouldRefreshWidgets(null))
        assertFalse(shouldRefreshWidgets(SyncOutcome.NothingChanged))
        assertFalse(shouldRefreshWidgets(SyncOutcome.AlreadyRunning))
        assertFalse(shouldRefreshWidgets(SyncOutcome.Synced(0, 0, 0, emptyList())))
    }

    @Test
    fun aSyncThatPushedPulledOrConflictedRedraws() {
        assertTrue(shouldRefreshWidgets(SyncOutcome.Synced(1, 0, 0, emptyList())))
        assertTrue(shouldRefreshWidgets(SyncOutcome.Synced(0, 3, 0, emptyList())))
        assertTrue(shouldRefreshWidgets(SyncOutcome.Synced(0, 0, 1, emptyList())))
    }

    @Test
    fun aDroppedNoteOrAnUndoneDeleteRedraws() {
        assertTrue(shouldRefreshWidgets(SyncOutcome.Synced(0, 0, 0, emptyList(), dropped = listOf(Dropped(1, 2, "t", "c")))))
        assertTrue(shouldRefreshWidgets(SyncOutcome.Synced(0, 0, 0, emptyList(), deleteUndone = 1)))
    }

    @Test
    fun onlyAnAuthFailureRedraws() {
        assertTrue(shouldRefreshWidgets(SyncOutcome.Failed(ApiError.Auth(401))))
        assertFalse(shouldRefreshWidgets(SyncOutcome.Failed(ApiError.Server(503))))
    }
}
