package app.citti.ui.browse

import app.citti.core.note.FolderCount
import app.citti.core.settings.MainMode
import app.citti.core.settings.TreeSort
import app.citti.core.sync.Progress
import app.citti.core.sync.SyncPhase
import app.citti.core.sync.SyncResultKind
import app.citti.core.sync.SyncStatus
import app.citti.core.sync.SyncTrigger
import app.citti.ui.components.RowBadge
import app.citti.ui.nav.Navigator
import app.citti.ui.nav.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun row(
    id: Long,
    title: String,
    folder: String = "",
    favorite: Boolean = false,
) = NoteRowItem(id, title, folder, excerpt = null, favorite = favorite, modified = id, badge = null)

/** In-memory stand-ins for the queries and settings that AppContainer wires from the DB. */
private class Fakes {
    val rows = MutableStateFlow(listOf(row(1, "b"), row(2, "a", folder = "x", favorite = true)))
    val counts = MutableStateFlow(listOf(FolderCount("", 1L, 1L), FolderCount("x", 1L, 2L), FolderCount("x/y", 2L, 3L)))
    val notes = MutableStateFlow(mapOf("" to listOf(row(1, "b")), "x" to listOf(row(2, "a", folder = "x"))))
    val status = MutableStateFlow<SyncStatus?>(null)
    val mode = MutableStateFlow(MainMode.LIST)
    val sort = MutableStateFlow(TreeSort.ALPHABETICAL)
    val triggers = mutableListOf<SyncTrigger>()
    val folderQueries = mutableListOf<String>()

    fun controller(scope: CoroutineScope) =
        BrowseController(
            scope = scope,
            listRows = { favoritesOnly -> rows.map { all -> if (favoritesOnly) all.filter { it.favorite } else all } },
            folderCounts = counts,
            folderNotes = { path -> folderNotes(path) },
            status = status,
            mainMode = mode,
            setMainMode = { mode.value = it },
            treeSort = sort,
            setTreeSort = { sort.value = it },
            collator = TitleCollator { a, b -> a.lowercase().compareTo(b.lowercase()) },
            sync = { triggers += it },
        )

    private fun folderNotes(path: String): Flow<List<NoteRowItem>> {
        folderQueries += path
        return notes.map { it[path].orEmpty() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseControllerTest {
    @Test
    fun onShownSyncsOnceAndSyncNowIsManual() =
        runTest(UnconfinedTestDispatcher()) {
            val f = Fakes()
            val c = f.controller(backgroundScope)
            c.onShown()
            c.onShown()
            c.syncNow()
            assertEquals(listOf(SyncTrigger.AppOpen, SyncTrigger.Manual), f.triggers)
        }

    @Test
    fun modeComesFromTheSettingAndSwitchingPersists() =
        runTest(UnconfinedTestDispatcher()) {
            val f = Fakes()
            f.mode.value = MainMode.TREE
            val c = f.controller(backgroundScope)
            assertEquals(MainMode.TREE, c.state.value.mode)
            c.setMode(MainMode.LIST)
            assertEquals(MainMode.LIST, f.mode.value)
            assertEquals(MainMode.LIST, c.state.value.mode)
        }

    @Test
    fun calendarIsTheThirdModeAndIsRemembered() =
        runTest(UnconfinedTestDispatcher()) {
            val f = Fakes()
            f.mode.value = MainMode.CALENDAR
            val c = f.controller(backgroundScope)
            assertEquals(MainMode.CALENDAR, c.state.value.mode)
            c.setMode(MainMode.TREE)
            assertEquals(MainMode.TREE, c.state.value.mode)
            c.setMode(MainMode.CALENDAR)
            assertEquals(MainMode.CALENDAR, f.mode.value)
            assertEquals(MainMode.CALENDAR, c.state.value.mode)
        }

    @Test
    fun theLaunchRouteFollowsTheMode() {
        assertEquals(Route.List(), MainMode.LIST.rootRoute())
        assertEquals(Route.Tree(), MainMode.TREE.rootRoute())
        assertEquals(Route.Calendar(), MainMode.CALENDAR.rootRoute())
    }

    @Test
    fun theModeSwitchSwapsTheRootInPlaceAndRemembersIt() =
        runTest(UnconfinedTestDispatcher()) {
            val f = Fakes()
            val c = f.controller(backgroundScope)
            val nav = Navigator(listOf(Route.Tree(), Route.Tree("x"), Route.Note(2)))
            c.switchMode(nav, MainMode.CALENDAR)
            assertEquals(listOf<Route>(Route.Calendar()), nav.stack.value)
            assertEquals(MainMode.CALENDAR, f.mode.value)

            // The Calendar segment again keeps the month shown.
            nav.replaceTop(Route.Calendar("2026-09"))
            c.switchMode(nav, MainMode.CALENDAR)
            assertEquals(listOf<Route>(Route.Calendar("2026-09")), nav.stack.value)

            c.switchMode(nav, MainMode.LIST)
            assertEquals(listOf<Route>(Route.List()), nav.stack.value)
            assertEquals(MainMode.LIST, f.mode.value)

            // From Favorites, List goes back to the whole List.
            nav.reset(listOf(Route.List(), Route.List(favoritesOnly = true)))
            c.switchMode(nav, MainMode.LIST)
            assertEquals(listOf<Route>(Route.List()), nav.stack.value)
        }

    @Test
    fun aCalendarRouteChangesNoQuery() =
        runTest(UnconfinedTestDispatcher()) {
            val f = Fakes()
            val c = f.controller(backgroundScope)
            c.show(Route.Calendar("2026-09"))
            assertEquals("", c.state.value.folder)
            assertFalse(c.state.value.favoritesOnly)
            assertEquals(listOf(""), f.folderQueries)
        }

    @Test
    fun listRouteDrivesTheFavoritesFilter() =
        runTest(UnconfinedTestDispatcher()) {
            val f = Fakes()
            val c = f.controller(backgroundScope)
            assertTrue(c.state.value.loaded)
            assertEquals(
                listOf(1L, 2L),
                c.state.value.list
                    .map { it.localId },
            )
            c.show(Route.List(favoritesOnly = true))
            assertTrue(c.state.value.favoritesOnly)
            assertEquals(
                listOf(2L),
                c.state.value.list
                    .map { it.localId },
            )
            f.rows.value = emptyList()
            assertEquals(emptyList(), c.state.value.list)
        }

    @Test
    fun folderRouteDrivesFolderNotes() =
        runTest(UnconfinedTestDispatcher()) {
            val f = Fakes()
            val c = f.controller(backgroundScope)
            assertEquals("", c.state.value.folder)
            assertEquals(
                listOf(1L),
                c.state.value.folderNotes
                    .map { it.localId },
            )
            c.show(Route.Tree("x"))
            assertEquals("x", c.state.value.folder)
            assertEquals(
                listOf(2L),
                c.state.value.folderNotes
                    .map { it.localId },
            )
            // A route that is neither List nor Tree, or the same Folder again, runs no new query.
            c.show(Route.Settings)
            c.show(Route.Tree("x"))
            assertEquals(listOf("", "x"), f.folderQueries)
        }

    @Test
    fun treeIsBuiltFromTheCountsWithRecursiveTotals() =
        runTest(UnconfinedTestDispatcher()) {
            val f = Fakes()
            val c = f.controller(backgroundScope)
            val tree = c.state.value.tree!!
            assertEquals(4, tree.totalNotes)
            assertEquals(listOf("x"), tree.children.map { it.path })
            assertEquals(3, tree.children.single().totalNotes)
        }

    @Test
    fun folderNotesAreSortedByTheCollatorThenLocalId() =
        runTest(UnconfinedTestDispatcher()) {
            val f = Fakes()
            f.notes.value = mapOf("" to listOf(row(5, "beta"), row(3, "Alpha"), row(4, "alpha"), row(1, "Gamma")))
            val c = f.controller(backgroundScope)
            assertEquals(
                listOf(3L, 4L, 5L, 1L),
                c.state.value.folderNotes
                    .map { it.localId },
            )
        }

    @Test
    fun sortOrderReordersTheTreeAndTheFolderNotesAndPersists() =
        runTest(UnconfinedTestDispatcher()) {
            val f = Fakes()
            f.counts.value = listOf(FolderCount("a", 1L, 5L), FolderCount("b", 1L, 9L))
            f.notes.value = mapOf("" to listOf(row(1, "a"), row(7, "b"), row(3, "c")))
            val c = f.controller(backgroundScope)
            assertEquals(
                listOf("a", "b"),
                c.state.value.tree!!
                    .children
                    .map { it.path },
            )
            assertEquals(
                listOf(1L, 7L, 3L),
                c.state.value.folderNotes
                    .map { it.localId },
            )
            c.setSort(TreeSort.MODIFIED)
            assertEquals(TreeSort.MODIFIED, f.sort.value)
            assertEquals(TreeSort.MODIFIED, c.state.value.treeSort)
            assertEquals(
                listOf("b", "a"),
                c.state.value.tree!!
                    .children
                    .map { it.path },
            )
            assertEquals(
                listOf(7L, 3L, 1L),
                c.state.value.folderNotes
                    .map { it.localId },
            )
        }

    @Test
    fun bannerPrecedence() {
        fun banner(status: SyncStatus?) = BrowseUiState(status = status).banner
        assertNull(banner(null))
        assertNull(banner(SyncStatus.Idle(100, SyncResultKind.Synced)))
        assertEquals(BrowseBanner.AuthFailed, banner(SyncStatus.AuthFailed(401, 100)))
        assertEquals(
            BrowseBanner.FirstSync(Progress(1240, 2232)),
            banner(SyncStatus.Running(SyncPhase.Pulling, Progress(1240, 2232), lastSuccessAt = null)),
        )
        // A later sync shows no banner: pull-to-refresh says that it runs.
        assertNull(banner(SyncStatus.Running(SyncPhase.Pulling, Progress(1, 2), lastSuccessAt = 100)))
        assertEquals(BrowseBanner.Offline(100), banner(SyncStatus.Offline(100)))
        assertEquals(BrowseBanner.Offline(null), banner(SyncStatus.Offline(null)))
        assertEquals(BrowseBanner.Failed(502, null), banner(SyncStatus.ServerError(502, 100)))
        assertEquals(BrowseBanner.Failed(null, "boom"), banner(SyncStatus.Failed("boom", null)))
    }

    @Test
    fun firstSyncAndSyncingFlags() {
        assertTrue(BrowseUiState(status = SyncStatus.Running(SyncPhase.Pulling, null, null)).firstSync)
        assertFalse(BrowseUiState(status = SyncStatus.Running(SyncPhase.Pulling, null, 1)).firstSync)
        assertTrue(BrowseUiState(status = SyncStatus.Running(SyncPhase.Pulling, null, 1)).syncing)
        assertFalse(BrowseUiState(status = SyncStatus.Offline(null)).syncing)
    }

    @Test
    fun rowBadgeFromSyncStateAndConflict() {
        fun badge(
            state: String,
            conflictOf: Long?,
        ) = noteRowItem(1, "t", "", null, false, 0, state, conflictOf).badge
        assertNull(badge("SYNCED", null))
        assertEquals(RowBadge.Pending, badge("DIRTY", null))
        assertEquals(RowBadge.Pending, badge("NEW", null))
        assertEquals(RowBadge.Conflict, badge("NEW", 7))
        assertEquals(RowBadge.Conflict, badge("SYNCED", 7))
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
