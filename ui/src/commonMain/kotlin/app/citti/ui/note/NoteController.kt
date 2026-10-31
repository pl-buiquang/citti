package app.citti.ui.note

import app.citti.core.markdown.Doc
import app.citti.core.markdown.ObsidianMarkdown
import app.citti.core.markdown.Task
import app.citti.core.markdown.ToolbarAction
import app.citti.core.note.EditorDoc
import app.citti.core.note.LinkTarget
import app.citti.core.note.NoteBadge
import app.citti.core.note.NoteEditor.SaveResult
import app.citti.core.sync.SyncStatus
import app.citti.ui.components.Snack
import app.citti.ui.markdown.EditorState
import app.citti.ui.nav.Navigator
import app.citti.ui.nav.Route
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The Conflict copy of a Note, or the original of a copy: the banner offers to open it. */
data class ConflictInfo(
    val localId: Long,
    val title: String,
    /** True when this Note is itself a Conflict copy. */
    val isCopy: Boolean,
)

data class NoteUiState(
    val title: String = "",
    val folder: String = "",
    val preview: Boolean = false,
    val readonly: Boolean = false,
    /** The text is not on the device yet (or is being replaced by the server version): editing is off. */
    val loading: Boolean = true,
    /** Loading, and the device cannot reach the server. */
    val offline: Boolean = false,
    val save: SaveIndicator = SaveIndicator.Saved,
    val badge: NoteBadge? = null,
    val conflict: ConflictInfo? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** The Preview model, parsed when [preview] is on; null until then. */
    val doc: Doc? = null,
    /** What each Note link of [doc] points at, filled in as they are resolved. */
    val links: Map<LinkRef, LinkTarget> = emptyMap(),
    val authFailed: Boolean = false,
    /** The Note no longer exists: the screen pops. */
    val gone: Boolean = false,
)

/**
 * State holder of one Note route entry. The text lives in [editor] while the screen is open and is written to the
 * database 500 ms after typing pauses and at once on [onLeave]; every write names the server version the text was
 * based on, and a write on a stale version becomes a Conflict copy (ADR 0003), never a silent overwrite.
 *
 * [route] is the entry's current route (the Edit/Preview toggle lives in it): the screen reports it with [show].
 * [leaveScope] outlives the entry, so the last 500 ms are saved after the entry is gone. [notify] shows a [Snack] on a
 * host that outlives the screen.
 */
@OptIn(FlowPreview::class)
class NoteController(
    private val scope: CoroutineScope,
    private val leaveScope: CoroutineScope,
    private val gateway: NoteGateway,
    status: Flow<SyncStatus?>,
    route: Route.Note,
    private val navigator: Navigator,
    private val notify: (Snack) -> Unit,
    clockMs: () -> Long,
    private val saveDelayMs: Long = SAVE_DELAY_MS,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val localId = route.localId
    private val route = MutableStateFlow(route)

    /** The text of the Note: owned here, so it survives the view and its recomposition. */
    val editor = EditorState(clockMs)

    /** Everything the state shows that is not the editor's or the sync's. */
    private data class Model(
        val row: EditorDoc? = null,
        val ready: Boolean = false,
        val reloading: Boolean = false,
        val unsaved: Boolean = false,
        val doc: Doc? = null,
        val links: Map<LinkRef, LinkTarget> = emptyMap(),
        val gone: Boolean = false,
    )

    private val model = MutableStateFlow(Model())
    private val saveLock = Mutex()
    private var baseEtag: String? = null
    private var lastSaved: String? = null

    // Which parse the Preview model on show is for; a newer one drops the older.
    private var parseJob: Job? = null

    private val effectivePreview: Flow<Boolean> =
        combine(
            this.route,
            model,
        ) { r, m -> r.preview || m.row?.readonly == true }.distinctUntilChanged()

    val state: StateFlow<NoteUiState> =
        combine(model, this.route, status, editor.canUndo, editor.canRedo) { m, r, s, undo, redo ->
            val row = m.row
            NoteUiState(
                title = row?.title.orEmpty(),
                folder = row?.folder.orEmpty(),
                preview = r.preview || row?.readonly == true,
                readonly = row?.readonly == true,
                loading = !m.ready || m.reloading,
                offline = s is SyncStatus.Offline,
                save = saveIndicator(m.unsaved || row?.pending == true, s),
                badge = row?.badge,
                conflict = row?.conflictCopy?.let { (id, title) -> ConflictInfo(id, title, isCopy = row.isConflictCopy) },
                canUndo = undo,
                canRedo = redo,
                doc = m.doc,
                links = m.links,
                authFailed = s is SyncStatus.AuthFailed,
                gone = m.gone,
            )
        }.stateIn(scope, SharingStarted.Eagerly, NoteUiState())

    init {
        scope.launch { gateway.observe().collect(::onRow) }
        scope.launch { editor.edits.collect { model.update { if (it.ready) it.copy(unsaved = true) else it } } }
        scope.launch { editor.edits.debounce(saveDelayMs).collect { saveNow() } }
        scope.launch {
            combine(effectivePreview, model.map { it.ready }.distinctUntilChanged()) { p, ready -> p && ready }
                .distinctUntilChanged()
                .collect { shown -> if (shown) parse() else model.update { it.copy(doc = null) } }
        }
    }

    /** The screen reports its current route (it changes with the Edit/Preview toggle). */
    fun show(route: Route.Note) {
        this.route.value = route
    }

    // ---- the row ----

    private suspend fun onRow(row: EditorDoc?) {
        if (row == null) return onGone()
        val text = row.text
        val before = model.value
        if (text == null) {
            model.update { it.copy(row = row) }
            return
        }
        when {
            !before.ready || before.reloading -> {
                // The first text, or the server's text after a refused edit.
                val caret = if (before.ready) caretAfterReload(editor.text, editor.selection.start, text) else text.length
                editor.replaceAll(text, caret)
                lastSaved = text
                baseEtag = row.baseEtag
                model.update { it.copy(row = row, ready = true, reloading = false, unsaved = false) }
                if (before.ready && effectivePreviewNow(row)) parse()
            }

            text == lastSaved -> {
                // Our own push (a new etag, the same text), or a pull that brought the text we already have.
                baseEtag = row.baseEtag
                model.update { it.copy(row = row) }
            }

            editor.text == lastSaved -> {
                // A new server version and nothing unsaved: show it, keep the caret on its line.
                editor.replaceAll(text, caretAfterReload(editor.text, editor.selection.start, text))
                lastSaved = text
                baseEtag = row.baseEtag
                model.update { it.copy(row = row, unsaved = false) }
                if (effectivePreviewNow(row)) parse()
            }

            else -> {
                // Unsaved text on top of an older version: the next save is refused and becomes a Conflict copy.
                model.update { it.copy(row = row) }
            }
        }
    }

    private fun effectivePreviewNow(row: EditorDoc) = route.value.preview || row.readonly

    private suspend fun onGone() {
        val m = model.value
        if (m.ready && !m.gone && editor.text != lastSaved && m.row?.readonly != true) {
            saveNow()
        }
        model.update { it.copy(gone = true) }
    }

    // ---- saving ----

    /** Saves the editor text now if it changed. Back, onStop and a route leaving the stack call [onLeave]. */
    suspend fun saveNow() {
        saveLock.withLock {
            val m = model.value
            val text = editor.text
            if (!m.ready || m.row?.readonly == true) return
            if (text == lastSaved) {
                model.update { it.copy(unsaved = false) }
                return
            }
            handle(gateway.save(text, baseEtag), text)
        }
    }

    private fun handle(
        result: SaveResult,
        text: String,
    ) {
        when (result) {
            SaveResult.Saved, SaveResult.ReadOnly -> {
                lastSaved = text
                model.update { it.copy(unsaved = editor.text != text) }
            }

            is SaveResult.Conflict -> {
                // The server changed: show its text as soon as it is here, and keep the local text in the copy.
                lastSaved = null
                model.update { it.copy(reloading = true, unsaved = false) }
                notify(Snack.ConflictCopy(result.copyTitle))
                model.value.row?.let { row -> scope.launch { onRow(row) } }
            }

            is SaveResult.Gone -> {
                notify(Snack.Deleted(result.copyTitle))
                lastSaved = text
                model.update { it.copy(unsaved = false) }
            }

            SaveResult.Mismatch -> {
                Unit
            }
        }
    }

    /** Flushes the text now, outside the entry's scope, so leaving the screen never drops the last 500 ms. */
    fun onLeave() {
        leaveScope.launch { saveNow() }
    }

    // ---- modes ----

    /** Flushes, then flips the route's `preview` (a read-only Note has no Edit mode). */
    fun togglePreview() {
        if (model.value.row?.readonly == true) return
        scope.launch {
            saveNow()
            val top = navigator.top
            if (top is Route.Note && top.localId == localId) navigator.replaceTop(top.copy(preview = !top.preview))
        }
    }

    fun onToolbar(action: ToolbarAction) {
        if (model.value.row?.readonly == true || !model.value.ready) return
        editor.toolbar(action)
    }

    fun undo() = editor.undo()

    fun redo() = editor.redo()

    // ---- preview ----

    private fun parse() {
        parseJob?.cancel()
        val text = editor.text
        parseJob =
            scope.launch {
                val doc = withContext(parseDispatcher) { ObsidianMarkdown.parse(text) }
                model.update { it.copy(doc = doc) }
                resolveLinks(doc)
            }
    }

    private suspend fun resolveLinks(doc: Doc) {
        val folder =
            model.value.row
                ?.folder
                .orEmpty()
        val known = model.value.links
        var batch = LinkedHashMap<LinkRef, LinkTarget>()
        for (ref in doc.linkRefs()) {
            if (ref in known) continue
            batch[ref] = gateway.resolve(folder, ref)
            if (batch.size >= LINK_BATCH) {
                val done = batch
                model.update { it.copy(links = it.links + done) }
                batch = LinkedHashMap()
            }
        }
        if (batch.isNotEmpty()) {
            val done = batch
            model.update { it.copy(links = it.links + done) }
        }
    }

    /** A tap on a checkbox in Preview mode: written at once, then the box is flipped in the shown model. */
    fun onTaskToggle(task: Task) {
        val m = model.value
        if (m.row?.readonly == true || !m.ready) return
        scope.launch {
            saveLock.withLock {
                val text = editor.text
                val expected = task.expected(text) ?: return@withLock
                if (text != lastSaved) {
                    // A pause shorter than the save timer: flush first so the row holds what is shown.
                    handle(gateway.save(text, baseEtag), text)
                    if (lastSaved != text) return@withLock
                }
                when (val result = gateway.toggleTask(task.box, expected, baseEtag)) {
                    SaveResult.Saved -> {
                        val saved = gateway.savedText() ?: return@withLock
                        editor.replaceAll(saved, editor.selection.start)
                        lastSaved = saved
                        model.update { it.copy(doc = it.doc?.withToggled(task.box), unsaved = false) }
                    }

                    SaveResult.Mismatch -> {
                        parse()
                    }

                    else -> {
                        handle(result, text)
                    }
                }
            }
        }
    }

    /** A tap on a Note link in Preview mode. */
    fun onLink(ref: LinkRef) {
        scope.launch {
            val folder =
                model.value.row
                    ?.folder
                    .orEmpty()
            val target = model.value.links[ref] ?: gateway.resolve(folder, ref)
            when (target) {
                is LinkTarget.Note -> if (target.localId != localId) navigator.push(Route.Note(target.localId))
                is LinkTarget.Unsynced -> notify(Snack.LinkUnsynced)
                LinkTarget.Missing -> notify(Snack.LinkMissing(ref.displayName()))
            }
        }
    }

    /** A tap on an Image in Preview mode opens the Image viewer. */
    fun onImage(link: String) {
        navigator.push(Route.ImageViewer(localId, link))
    }

    /** The banner's Open: the Conflict copy, or the original of a copy. */
    fun openConflict() {
        state.value.conflict?.let { navigator.push(Route.Note(it.localId)) }
    }

    private fun LinkRef.displayName(): String =
        when (this) {
            is LinkRef.Wiki -> {
                target
                    .substringBefore('#')
                    .substringBefore('^')
                    .substringAfterLast('/')
                    .removeSuffix(".md")
            }

            is LinkRef.Markdown -> {
                url.substringBefore('#').substringAfterLast('/').removeSuffix(".md")
            }
        }

    companion object {
        const val SAVE_DELAY_MS = 500L
        private const val LINK_BATCH = 40
    }
}
