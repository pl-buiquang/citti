package app.citti.ui.list

import app.citti.core.sync.Progress
import app.citti.core.sync.SyncPhase
import app.citti.core.sync.SyncResultKind
import app.citti.core.sync.SyncStatus
import app.citti.core.sync.SyncTrigger
import app.citti.ui.components.RowBadge
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NoteListControllerTest {
    @Test
    fun onShownSyncsOnceAndSyncNowIsManual() =
        runTest(UnconfinedTestDispatcher()) {
            val triggers = mutableListOf<SyncTrigger>()
            val c = NoteListController(backgroundScope, MutableStateFlow(emptyList()), MutableStateFlow(null)) { triggers += it }
            c.onShown()
            c.onShown()
            c.syncNow()
            assertEquals(listOf(SyncTrigger.AppOpen, SyncTrigger.Manual), triggers)
        }

    @Test
    fun stateCombinesNotesAndStatus() =
        runTest(UnconfinedTestDispatcher()) {
            val notes = MutableStateFlow(listOf(NoteListItem(1, "A", "x/y", false, RowBadge.Pending)))
            val status = MutableStateFlow<SyncStatus?>(null)
            val c = NoteListController(backgroundScope, notes, status) {}
            assertEquals(1, c.state.value.items.size)
            status.value = SyncStatus.Running(SyncPhase.Pulling, Progress(3, 10), null)
            assertTrue(c.state.value.syncing)
            notes.value = emptyList()
            assertTrue(
                c.state.value.items
                    .isEmpty(),
            )
        }

    @Test
    fun syncLineVariants() {
        val now = 10_000L
        assertEquals(SyncLine.NeverSynced, syncLine(null, now))
        assertEquals(SyncLine.NeverSynced, syncLine(SyncStatus.Idle(null, null), now))
        assertEquals(SyncLine.Synced(Age.JustNow, 0), syncLine(SyncStatus.Idle(now - 10, SyncResultKind.Synced), now))
        assertEquals(SyncLine.Synced(Age.Minutes, 2), syncLine(SyncStatus.Idle(now - 130, SyncResultKind.Synced), now))
        assertEquals(SyncLine.Synced(Age.Hours, 2), syncLine(SyncStatus.Idle(now - 7300, SyncResultKind.Synced), now))
        assertEquals(SyncLine.Synced(Age.Days, 1), syncLine(SyncStatus.Idle(now - 90_000, SyncResultKind.Synced), now))
        assertEquals(SyncLine.Offline, syncLine(SyncStatus.Offline(null), now))
        assertEquals(SyncLine.AuthFailed, syncLine(SyncStatus.AuthFailed(401, null), now))
        assertEquals(SyncLine.ServerError(502), syncLine(SyncStatus.ServerError(502, null), now))
        assertEquals(SyncLine.Failed, syncLine(SyncStatus.Failed("x", null), now))
        assertEquals(SyncLine.Syncing(Progress(1, 2)), syncLine(SyncStatus.Running(SyncPhase.Pulling, Progress(1, 2), null), now))
    }

    @Test
    fun formatCountGroupsThousands() {
        assertEquals("0", formatCount(0))
        assertEquals("999", formatCount(999))
        assertEquals("1,240", formatCount(1240))
        assertEquals("2,232", formatCount(2232))
        assertEquals("1,000,000", formatCount(1_000_000))
    }
}
