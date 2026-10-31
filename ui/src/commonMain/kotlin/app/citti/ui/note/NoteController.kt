package app.citti.ui.note

import app.citti.core.api.ApiError
import app.citti.core.dav.UploadResult
import app.citti.core.markdown.Doc
import app.citti.core.markdown.ObsidianMarkdown
import app.citti.core.markdown.Selection
import app.citti.core.markdown.Task
import app.citti.core.markdown.TextOps
import app.citti.core.markdown.ToolbarAction
import app.citti.core.note.EditorDoc
import app.citti.core.note.LinkTarget
import app.citti.core.note.NameRules
import app.citti.core.note.NoteBadge
import app.citti.core.note.NoteEditor.SaveResult
import app.citti.core.note.RenameResult
import app.citti.core.sync.SyncStatus
import app.citti.ui.components.FolderPickerState
import app.citti.ui.components.MenuItem
import app.citti.ui.components.Snack
import app.citti.ui.create.NewNoteController
import app.citti.ui.create.NoteCreation
import app.citti.ui.create.linkPrefill
import app.citti.ui.image.ImageUpload
import app.citti.ui.image.PickedImage
import app.citti.ui.markdown.EditorState
import app.citti.ui.markdown.Marker
import app.citti.ui.nav.Navigator
import app.citti.ui.nav.Route
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

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
    /** Insert image: the Camera / Gallery sheet is open. */
    val imageSheet: Boolean = false,
    /** An Image is being uploaded; its link goes in when it is done. */
    val uploading: Boolean = false,
    val favorite: Boolean = false,
    /** The ⋮ menu is open, with [menu]'s items. */
    val menuOpen: Boolean = false,
    val menu: List<MenuItem> = emptyList(),
    /** The dialog or sheet of a ⋮ menu item, over the screen. */
    val dialog: NoteDialog? = null,
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
    /** Insert image's upload; null while signed out (the upload then fails as offline). */
    private val imageUpload: ImageUpload? = null,
    /** Where [editor] may be changed from when an upload ends (Android: the main thread, the view's). */
    private val editorContext: CoroutineContext = EmptyCoroutineContext,
    /** The ⋮ menu's Favorite, Rename, Move, Delete and Note info; null: the menu acts on nothing. */
    private val menuGateway: NoteMenuGateway? = null,
    /** "Create note" from an unresolved link; null: no New note flow on this screen. */
    creation: NoteCreation? = null,
) {
    private val localId = route.localId
    private val route = MutableStateFlow(route)

    /** The text of the Note: owned here, so it survives the view and its recomposition. */
    val editor = EditorState(clockMs)

    /** The New note dialog of "Create note" on an unresolved link (M5 spec §3); null without [NoteCreation]. */
    val newNote: NewNoteController? = creation?.let { NewNoteController(scope, it, navigator) }

    /** Everything the state shows that is not the editor's or the sync's. */
    private data class Model(
        val row: EditorDoc? = null,
        val ready: Boolean = false,
        val reloading: Boolean = false,
        val unsaved: Boolean = false,
        val doc: Doc? = null,
        val links: Map<LinkRef, LinkTarget> = emptyMap(),
        val gone: Boolean = false,
        val imageSheet: Boolean = false,
        val uploading: Boolean = false,
        val menuOpen: Boolean = false,
        val dialog: NoteDialog? = null,
        /** Deleted from the ⋮ menu: nothing is saved any more, and the screen pops. */
        val deleted: Boolean = false,
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
                imageSheet = m.imageSheet,
                uploading = m.uploading,
                favorite = row?.favorite == true,
                menuOpen = m.menuOpen,
                menu = menu(row, m.ready),
                dialog = m.dialog,
            )
        }.stateIn(scope, SharingStarted.Eagerly, NoteUiState())

    /** The server's Note this New note was dropped for at push ([NoteGateway.dropped]), once it was. */
    private val droppedAs = MutableStateFlow<Long?>(null)

    init {
        scope.launch { gateway.dropped().collect { droppedAs.value = it } }
        scope.launch { gateway.observe().collect(::onRow) }
        scope.launch { editor.edits.collect { model.update { if (it.ready) it.copy(unsaved = true) else it } } }
        scope.launch { editor.edits.debounce(saveDelayMs).collect { saveNow() } }
        scope.launch {
            combine(effectivePreview, model.map { it.ready }.distinctUntilChanged()) { p, ready -> p && ready }
                .distinctUntilChanged()
                .collect { shown -> if (shown) parse() else model.update { it.copy(doc = null) } }
        }
        scope.launch {
            combine(this@NoteController.route, model.map { it.ready }.distinctUntilChanged()) { r, ready -> r.focus && ready }
                .distinctUntilChanged()
                .collect { if (it) applyFocus() }
        }
    }

    /** The screen reports its current route (it changes with the Edit/Preview toggle). */
    fun show(route: Route.Note) {
        this.route.value = route
    }

    /** `Route.Note.focus`: focus the editor once the text is here, then clear the flag so it is applied only once. */
    private fun applyFocus() {
        if (model.value.row?.readonly != true) editor.requestFocus()
        val top = navigator.top
        if (top is Route.Note && top.localId == localId && top.focus) navigator.replaceTop(top.copy(focus = false))
    }

    // ---- the row ----

    /** The missing text was asked for once ([NoteGateway.fetchText]). */
    private var fetchedText = false

    private suspend fun onRow(row: EditorDoc?) {
        if (row == null) return onGone()
        val text = row.text
        val before = model.value
        if (text == null) {
            model.update { it.copy(row = row) }
            if (!fetchedText) {
                fetchedText = true
                scope.launch { gateway.fetchText() }
            }
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

    private fun effectivePreviewNow() = model.value.row?.let(::effectivePreviewNow) ?: route.value.preview

    private suspend fun onGone() {
        val m = model.value
        val unsaved = m.ready && !m.gone && !m.deleted && editor.text != lastSaved && m.row?.readonly != true
        if (unsaved) saveNow()
        // A pending New note the push dropped (M5 spec §3): the event can come just after the row went.
        val server =
            droppedAs.value
                ?: if (m.row?.pending == true && !m.deleted) {
                    withTimeoutOrNull(DROP_WAIT_MS) { droppedAs.filterNotNull().first() }
                } else {
                    null
                }
        // With unsaved text, the Gone rule's Conflict copy snackbar is the one to show.
        if (server != null && switchToServerNote(server, snack = !unsaved)) return
        model.update { it.copy(gone = true) }
    }

    /** Dropped while open: the screen becomes the server's Note. False when this Note is no longer on top. */
    private fun switchToServerNote(
        serverLocalId: Long,
        snack: Boolean,
    ): Boolean {
        val top = navigator.top
        if (top !is Route.Note || top.localId != localId) return false
        navigator.replaceTop(Route.Note(serverLocalId, preview = top.preview))
        if (snack) notify(Snack.AlreadyOnServer)
        return true
    }

    // ---- saving ----

    /** Saves the editor text now if it changed. Back, onStop and a route leaving the stack call [onLeave]. */
    suspend fun saveNow() {
        saveLock.withLock {
            val m = model.value
            val text = editor.text
            if (!m.ready || m.row?.readonly == true || m.deleted) return
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
        // Insert image opens a sheet instead of editing the text.
        if (action == ToolbarAction.Image) openImageSheet() else editor.toolbar(action)
    }

    // ---- insert image ----

    // Where the link of the Image being picked goes: the caret when the picker opened.
    private var pickMarker: Marker? = null
    private var uploadJob: Job? = null

    /** Insert image (toolbar or ⋮ menu): the Camera / Gallery sheet. Not on a read-only Note, nor during an upload. */
    fun openImageSheet() {
        val m = model.value
        if (m.row?.readonly == true || !m.ready || m.uploading) return
        model.update { it.copy(imageSheet = true) }
    }

    fun dismissImageSheet() = model.update { it.copy(imageSheet = false) }

    /** A row of the sheet: the platform picker opens now, and the link will go where the caret is now. */
    fun pickImage() {
        dismissImageSheet()
        pickMarker?.release()
        pickMarker = editor.markCaret()
    }

    /**
     * The picker's result (null: cancelled). The upload runs in [leaveScope], so leaving the Note does not stop it;
     * the link then goes in at the marker, as one undo step, or through `NoteEditor.insertAt` once the entry is gone.
     * Offline or a failed upload: a snackbar, and the text is untouched.
     */
    fun onImagePicked(image: PickedImage?) {
        val marker = pickMarker
        pickMarker = null
        if (image == null || model.value.uploading) {
            marker?.release()
            image?.dispose()
            return
        }
        // No marker when the activity was recreated while the picker was up: the caret now.
        val at = marker ?: editor.markCaret()
        val folder =
            model.value.row
                ?.folder
                .orEmpty()
        model.update { it.copy(uploading = true) }
        uploadJob =
            leaveScope.launch {
                try {
                    val result =
                        try {
                            imageUpload?.upload(image.load(), folder) ?: UploadResult.Failed(ApiError.DavNotReady)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            notify(Snack.UploadFailed(e.message ?: e::class.simpleName.orEmpty()))
                            null
                        } finally {
                            image.dispose()
                        }
                    when (result) {
                        is UploadResult.Uploaded -> insertLink(at.offset, result.link)
                        is UploadResult.Failed -> notify(result.error.toUploadSnack())
                        null -> Unit
                    }
                } finally {
                    at.release()
                    model.update { it.copy(uploading = false) }
                    uploadJob = null
                }
            }
    }

    /** The chip's Cancel: the upload stops and nothing is inserted (a file the server already took stays there). */
    fun cancelUpload() {
        uploadJob?.cancel()
    }

    private suspend fun insertLink(
        offset: Int,
        link: String,
    ) {
        if (scope.isActive) {
            withContext(editorContext) {
                saveLock.withLock {
                    // The user's selection stays where it was, after the link when it was at or past the marker.
                    val sel = editor.selection
                    val edit = TextOps.insertBlock(editor.text, offset, link)
                    val length = edit.replacement.length

                    fun moved(o: Int) = if (o >= edit.start) o + length else o
                    editor.apply(edit.copy(selection = Selection(moved(sel.start), moved(sel.end))))
                }
            }
            if (effectivePreviewNow()) parse()
            return
        }
        // The entry is gone. Its last text not saved yet (the leave's save is still queued): the link goes into it.
        saveLock.withLock {
            val text = editor.text
            if (model.value.ready && text != lastSaved) {
                editor.apply(TextOps.insertBlock(text, offset, link))
                val withLink = editor.text
                handle(gateway.save(withLink, baseEtag), withLink)
                return
            }
            // Else the row holds that text: the link goes into the stored text.
            val result = gateway.insertAt(offset, link, baseEtag)
            if (result == SaveResult.Saved) {
                gateway.savedText()?.let {
                    editor.replaceAll(it)
                    lastSaved = it
                }
            } else {
                handle(result, text)
            }
        }
    }

    // ---- the ⋮ menu ----

    /** The ⋮ button: the menu, unless a dialog is up. */
    fun openMenu() {
        val m = model.value
        if (m.dialog != null || m.deleted) return
        model.update { it.copy(menuOpen = true) }
    }

    fun dismissMenu() = model.update { it.copy(menuOpen = false) }

    /** A tap on a menu item. Items ending in "…" open a dialog; the others act at once and confirm with a toast. */
    fun onMenu(item: MenuItem) {
        dismissMenu()
        if (item !in menu(model.value.row, model.value.ready)) return
        when (item) {
            MenuItem.Favorite -> toggleFavorite()
            MenuItem.Move -> openMove()
            MenuItem.Rename -> openRename()
            MenuItem.InsertImage -> openImageSheet()
            MenuItem.Info -> openInfo()
            MenuItem.Delete -> model.update { it.copy(dialog = NoteDialog.DeleteConfirm) }
        }
    }

    /** Back: the menu closes, else the Move picker goes up one level (closing at the root), else the dialog closes. */
    fun back(): Boolean {
        val m = model.value
        val dialog = m.dialog
        when {
            m.menuOpen -> {
                dismissMenu()
            }

            dialog is NoteDialog.Move && !dialog.checking -> {
                setDialog(
                    dialog.picker.back()?.let {
                        dialog.copy(picker = it, error = null, clashFolder = null)
                    },
                )
            }

            dialog != null -> {
                dismissDialog()
            }

            else -> {
                return false
            }
        }
        return true
    }

    /** Closes the dialog; not while its server check runs (the button shows the progress). */
    fun dismissDialog() {
        val d = model.value.dialog
        if ((d is NoteDialog.Rename && d.checking) || (d is NoteDialog.Move && d.checking)) return
        setDialog(null)
    }

    private fun setDialog(dialog: NoteDialog?) = model.update { it.copy(dialog = dialog) }

    private inline fun <reified T : NoteDialog> editDialog(crossinline change: (T) -> T) {
        model.update { m -> (m.dialog as? T)?.let { m.copy(dialog = change(it)) } ?: m }
    }

    /** Favorite: toggled at once, with a toast. The row becomes DIRTY (a NEW one stays NEW) and is pushed. */
    fun toggleFavorite() {
        val row = model.value.row ?: return
        if (row.readonly) return
        val favorite = !row.favorite
        scope.launch {
            if (menuGateway?.setFavorite(favorite) == true) {
                notify(if (favorite) Snack.FavoriteAdded else Snack.FavoriteRemoved)
            }
        }
    }

    // Rename…

    fun openRename() {
        val row = model.value.row ?: return
        if (row.readonly) return
        setDialog(NoteDialog.Rename(row.title))
    }

    fun onRenameText(text: String) =
        editDialog<NoteDialog.Rename> {
            // The field also reports selection and IME composition changes: only a new text clears the error.
            if (it.checking || it.text == text) it else it.copy(text = text, error = null, clashFolder = null)
        }

    /** Flushes the editor, then renames; a clash or an invalid title keeps the dialog open with the error. */
    fun confirmRename() {
        val dialog = model.value.dialog as? NoteDialog.Rename ?: return
        if (dialog.checking) return
        val title = dialog.text.trim()
        NameRules.validateTitle(title)?.let { e ->
            setDialog(dialog.copy(error = e, clashFolder = null))
            return
        }
        setDialog(dialog.copy(checking = true, error = null, clashFolder = null))
        scope.launch {
            saveNow()
            val result = menuGateway?.rename(title) ?: RenameResult.Gone
            editDialog<NoteDialog.Rename> { it.copy(checking = false) }
            when (result) {
                RenameResult.Done, RenameResult.Unchanged, RenameResult.ReadOnly, RenameResult.Gone -> setDialog(null)
                is RenameResult.Clash -> editDialog<NoteDialog.Rename> { it.copy(text = title, clashFolder = result.folder) }
                is RenameResult.Invalid -> editDialog<NoteDialog.Rename> { it.copy(error = result.reason) }
            }
        }
    }

    // Move…

    fun openMove() {
        val row = model.value.row ?: return
        if (row.readonly) return
        setDialog(NoteDialog.Move(FolderPickerState(row.folder)))
    }

    private fun editPicker(change: (FolderPickerState) -> FolderPickerState) =
        editDialog<NoteDialog.Move> { if (it.checking) it else it.copy(picker = change(it.picker), error = null, clashFolder = null) }

    fun pickerOpen(path: String) = editPicker { it.open(path) }

    fun pickerNewFolder() = editPicker { it.startNewFolder() }

    fun pickerNewFolderText(text: String) = editPicker { it.onNewFolder(text) }

    fun pickerNewFolderDone() {
        val picker = (model.value.dialog as? NoteDialog.Move)?.picker ?: return
        val gateway = menuGateway ?: return
        scope.launch {
            val normalised = gateway.normaliseFolder(picker.typedPath())
            editPicker { if (it == picker) it.withNewFolder(normalised) else it }
        }
    }

    /** "Move here": flushes the editor, then moves into the picker's Folder; a clash keeps the picker open. */
    fun confirmMove() {
        val dialog = model.value.dialog as? NoteDialog.Move ?: return
        if (dialog.checking) return
        val folder = dialog.picker.path
        setDialog(dialog.copy(checking = true, error = null, clashFolder = null))
        scope.launch {
            saveNow()
            val result = menuGateway?.move(folder) ?: RenameResult.Gone
            editDialog<NoteDialog.Move> { it.copy(checking = false) }
            when (result) {
                RenameResult.Done, RenameResult.Unchanged, RenameResult.ReadOnly, RenameResult.Gone -> setDialog(null)
                is RenameResult.Clash -> editDialog<NoteDialog.Move> { it.copy(clashFolder = result.folder) }
                is RenameResult.Invalid -> editDialog<NoteDialog.Move> { it.copy(error = result.reason, errorPath = folder) }
            }
        }
    }

    // Delete

    /**
     * The confirm dialog's Delete: flushes the editor, deletes (a never-pushed Note outright, any other one is
     * pushed to the server's trash), and the screen pops with a toast on the app's snackbar.
     */
    fun confirmDelete() {
        if (model.value.dialog != NoteDialog.DeleteConfirm || model.value.row?.readonly == true) return
        setDialog(null)
        scope.launch {
            saveNow()
            if (menuGateway?.delete() == true) {
                model.update { it.copy(deleted = true, gone = true, menuOpen = false, dialog = null) }
                notify(Snack.NoteDeleted)
            }
        }
    }

    // Note info

    /** The Note info sheet, with the counts of the text as saved (the editor is flushed first). */
    fun openInfo() {
        scope.launch {
            saveNow()
            val info = menuGateway?.info() ?: return@launch
            setDialog(NoteDialog.Info(info))
        }
    }

    /** Note info's "Open conflict copy". */
    fun openInfoConflict() {
        val info = (model.value.dialog as? NoteDialog.Info)?.info ?: return
        val (id, _) = info.conflictCopy ?: return
        setDialog(null)
        navigator.push(Route.Note(id))
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
                is LinkTarget.Note -> {
                    if (target.localId != localId) navigator.push(Route.Note(target.localId))
                }

                is LinkTarget.Unsynced -> {
                    notify(Snack.LinkUnsynced)
                }

                LinkTarget.Missing -> {
                    val prefill = linkPrefill(ref, folder)
                    notify(Snack.LinkMissing(ref.displayName(), prefill.folder, prefill.title))
                }
            }
        }
    }

    /**
     * The Create note action of [snack]: flushes the text, then opens the New note dialog prefilled; the same flow as
     * the New note button follows.
     */
    fun createFromLink(snack: Snack.LinkMissing) {
        val newNote = newNote ?: return
        scope.launch { saveNow() }
        newNote.open(snack.folder, snack.title)
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

    private fun ApiError.toUploadSnack(): Snack =
        when (this) {
            is ApiError.Network, ApiError.DavNotReady -> Snack.UploadOffline
            is ApiError.Auth -> Snack.UploadFailed("HTTP $status")
            is ApiError.Server -> Snack.UploadFailed("HTTP $status")
            is ApiError.Http -> Snack.UploadFailed("HTTP $status")
            ApiError.NotFound -> Snack.UploadFailed("HTTP 404")
            is ApiError.BadResponse -> Snack.UploadFailed(message ?: "bad response")
            ApiError.NotesAppMissing, ApiError.InvalidUrl, ApiError.InsecureUrl -> Snack.UploadFailed(toString())
        }

    companion object {
        /** The ⋮ menu of [row]: a read-only Note gets only Note info (the push skips read-only Notes). */
        fun menu(
            row: EditorDoc?,
            ready: Boolean,
        ): List<MenuItem> =
            when {
                row == null -> emptyList()

                row.readonly -> listOf(MenuItem.Info)

                // Insert image needs the text on the device.
                !ready -> MenuItem.entries - MenuItem.InsertImage

                else -> MenuItem.entries
            }

        const val SAVE_DELAY_MS = 500L

        /** How long a gone pending Note waits for its [NoteGateway.dropped] event before it pops. */
        const val DROP_WAIT_MS = 1_000L
        private const val LINK_BATCH = 40
    }
}
