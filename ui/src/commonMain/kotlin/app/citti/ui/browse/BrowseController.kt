package app.citti.ui.browse

import app.citti.core.note.FolderCount
import app.citti.core.note.FolderNode
import app.citti.core.note.buildFolderTree
import app.citti.core.settings.MainMode
import app.citti.core.settings.TreeSort
import app.citti.core.sync.Progress
import app.citti.core.sync.SyncStatus
import app.citti.core.sync.SyncTrigger
import app.citti.ui.nav.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The one banner the main view shows (§8.6 of the M3 spec), most important first. */
sealed interface BrowseBanner {
    data object AuthFailed : BrowseBanner

    /** The first sync of the Vault: "Syncing 1,240 / 2,232 notes". */
    data class FirstSync(
        val progress: Progress?,
    ) : BrowseBanner

    /** [lastSuccessAt] (epoch seconds) gives the age of the last sync, when there is one. */
    data class Offline(
        val lastSuccessAt: Long?,
    ) : BrowseBanner

    /** [status] is the HTTP status of a server error, [message] the error of any other failure; Details shows them. */
    data class Failed(
        val status: Int?,
        val message: String?,
    ) : BrowseBanner
}

data class BrowseUiState(
    val mode: MainMode = MainMode.LIST,
    val list: List<NoteRowItem> = emptyList(),
    val favoritesOnly: Boolean = false,
    /** The whole visible tree, for Tree mode and the drawer shortcuts. Null until the counts load. */
    val tree: FolderNode? = null,
    /** The Tree Folder that [folderNotes] were loaded for ("" is the Vault root). */
    val folder: String = "",
    val treeSort: TreeSort = TreeSort.ALPHABETICAL,
    val folderNotes: List<NoteRowItem> = emptyList(),
    /** Null until the sync stack exists. */
    val status: SyncStatus? = null,
    /** True once the List rows have loaded (for [favoritesOnly]). */
    val loaded: Boolean = false,
) {
    val syncing: Boolean get() = status is SyncStatus.Running

    /** The first sync of the Vault is running: no sync has succeeded yet. */
    val firstSync: Boolean get() = status is SyncStatus.Running && status.lastSuccessAt == null

    val banner: BrowseBanner?
        get() =
            when (val s = status) {
                is SyncStatus.AuthFailed -> BrowseBanner.AuthFailed
                is SyncStatus.Running -> if (s.lastSuccessAt == null) BrowseBanner.FirstSync(s.progress) else null
                is SyncStatus.Offline -> BrowseBanner.Offline(s.lastSuccessAt)
                is SyncStatus.ServerError -> BrowseBanner.Failed(s.status, null)
                is SyncStatus.Failed -> BrowseBanner.Failed(null, s.message)
                is SyncStatus.Idle, null -> null
            }
}

/** What the current route asks the controller to show. */
private data class View(
    val favoritesOnly: Boolean = false,
    val folder: String = "",
)

/**
 * State holder of the main view, one per signed-in session and shared by every List, Tree and Calendar route entry
 * (the Calendar takes its banner and sync state from here, its month and days from its own controller).
 * The current route drives it through [show]. [listRows], [folderCounts] and [folderNotes] come from the local DB,
 * [status] from the SyncEngine, [mainMode] and [setMainMode] from the `ui.main-mode` setting. [treeSort] and [setTreeSort] come from
 * the `ui.tree-sort` setting. [folderNotes] are sorted per the [TreeSort] (by title with [collator], or newest first),
 * then by local id so that the order is stable. [sync] runs a sync with the given trigger.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseController(
    private val scope: CoroutineScope,
    listRows: (favoritesOnly: Boolean) -> Flow<List<NoteRowItem>>,
    folderCounts: Flow<List<FolderCount>>,
    folderNotes: (path: String) -> Flow<List<NoteRowItem>>,
    status: Flow<SyncStatus?>,
    mainMode: Flow<MainMode>,
    private val setMainMode: suspend (MainMode) -> Unit,
    treeSort: Flow<TreeSort>,
    private val setTreeSort: suspend (TreeSort) -> Unit,
    collator: TitleCollator,
    private val sync: suspend (SyncTrigger) -> Unit,
) {
    private val view = MutableStateFlow(View())

    // Each part carries the filter or Folder it was loaded for, so the state never pairs a route with rows
    // still loaded for the previous one.
    private val list: Flow<ListPart?> =
        view
            .map { it.favoritesOnly }
            .distinctUntilChanged()
            .flatMapLatest { favoritesOnly -> listRows(favoritesOnly).map { ListPart(favoritesOnly, it) } }
            .onStart<ListPart?> { emit(null) }

    private val sort: Flow<TreeSort> = treeSort.onStart { emit(TreeSort.ALPHABETICAL) }.distinctUntilChanged()

    private val tree: Flow<FolderNode?> =
        combine(folderCounts, sort) { counts, order ->
            val rows = counts.map { it.category to it.notes }
            buildFolderTree(rows, collator, counts.associate { it.category to it.newest }, order)
        }.onStart<FolderNode?> { emit(null) }

    private val byTitle = compareBy<NoteRowItem, String>(collator) { it.title }

    private fun noteOrder(sort: TreeSort) =
        when (sort) {
            TreeSort.ALPHABETICAL -> byTitle.thenBy { it.localId }
            TreeSort.MODIFIED -> compareByDescending<NoteRowItem> { it.modified }.then(byTitle).thenBy { it.localId }
        }

    private val notesInFolder: Flow<FolderPart?> =
        view
            .map { it.folder }
            .distinctUntilChanged()
            .flatMapLatest { folder ->
                combine(folderNotes(folder), sort) { notes, order -> FolderPart(folder, notes.sortedWith(noteOrder(order))) }
            }.onStart<FolderPart?> { emit(null) }

    val state: StateFlow<BrowseUiState> =
        combine(
            list,
            tree,
            notesInFolder,
            combine(mainMode.onStart { emit(MainMode.LIST) }.distinctUntilChanged(), sort, ::Pair),
            status.onStart { emit(null) },
        ) { list, tree, folder, (mode, order), st ->
            BrowseUiState(
                mode = mode,
                list = list?.rows.orEmpty(),
                favoritesOnly = list?.favoritesOnly ?: false,
                tree = tree,
                folder = folder?.folder ?: "",
                treeSort = order,
                folderNotes = folder?.notes.orEmpty(),
                status = st,
                loaded = list != null,
            )
        }.stateIn(scope, SharingStarted.Eagerly, BrowseUiState())

    private class ListPart(
        val favoritesOnly: Boolean,
        val rows: List<NoteRowItem>,
    )

    private class FolderPart(
        val folder: String,
        val notes: List<NoteRowItem>,
    )

    /**
     * The route on top of the stack: List sets the favorites filter, Tree the Folder. Other routes (the Calendar
     * included) change nothing.
     */
    fun show(route: Route) {
        when (route) {
            is Route.List -> view.value = view.value.copy(favoritesOnly = route.favoritesOnly)
            is Route.Tree -> view.value = view.value.copy(folder = route.folder)
            else -> Unit
        }
    }

    /** Persists the Mode the main view opens in next time, Calendar included ([switchMode] also resets the back stack). */
    fun setMode(mode: MainMode) {
        scope.launch { setMainMode(mode) }
    }

    /** Persists the Tree mode's sort order. */
    fun setSort(sort: TreeSort) {
        scope.launch { setTreeSort(sort) }
    }

    private var opened = false

    /** Call when the main view appears; syncs once per controller, however often the screen is recomposed. */
    fun onShown() {
        if (opened) return
        opened = true
        run(SyncTrigger.AppOpen)
    }

    /** Pull-to-refresh and the banner's Retry. */
    fun syncNow() = run(SyncTrigger.Manual)

    private fun run(trigger: SyncTrigger) {
        scope.launch { sync(trigger) }
    }
}
