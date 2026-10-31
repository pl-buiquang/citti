package app.citti.ui.create

import app.citti.core.note.InvalidNameException
import app.citti.core.note.NameError
import app.citti.core.note.NameRules
import app.citti.core.note.NoteCreator
import app.citti.core.note.NoteCreator.Check
import app.citti.core.settings.ExclusionRepository
import app.citti.ui.components.FolderPickerState
import app.citti.ui.nav.Navigator
import app.citti.ui.nav.Route
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What [NewNoteController] needs from `NoteCreator` (ADR 0006), so the controller is tested without a database. */
interface NoteCreation {
    /** `NameRules.normaliseFolder` against the current Unsynced folders. */
    suspend fun normaliseFolder(path: String): Result<String>

    suspend fun check(
        folder: String,
        title: String,
    ): Check

    /** The first free `Title (n)` in [folder]. */
    suspend fun freeTitle(
        folder: String,
        title: String,
    ): String

    /** Creates the empty Note and schedules its push; null while signed out. */
    suspend fun create(
        folder: String,
        title: String,
    ): Long?
}

/** [NoteCreation] over the session's [NoteCreator] (null while signed out), with the database reads on [io]. */
class CoreNoteCreation(
    private val creator: () -> NoteCreator?,
    private val exclusions: ExclusionRepository,
    private val io: CoroutineDispatcher,
) : NoteCreation {
    override suspend fun normaliseFolder(path: String): Result<String> = withContext(io) { NameRules.normaliseFolder(path, exclusions) }

    override suspend fun check(
        folder: String,
        title: String,
    ): Check = creator()?.let { withContext(io) { it.check(folder, title) } } ?: Check.Unchecked

    override suspend fun freeTitle(
        folder: String,
        title: String,
    ): String = creator()?.let { withContext(io) { it.freeTitle(folder, title) } } ?: title

    override suspend fun create(
        folder: String,
        title: String,
    ): Long? = creator()?.let { withContext(io) { it.create(folder, title, content = "") } }
}

/** The New note dialog found a Note with this title in [folder]: open it ([localId]) or create [freeTitle]. */
data class Clash(
    val localId: Long,
    val folder: String,
    val freeTitle: String,
)

/**
 * The New note dialog. [error] is about the title, or about the Folder when [errorPath] is set; [checking] is the
 * server check (inline progress on Create); [picker] is the Folder picker opened by Change.
 */
data class NewNoteState(
    val title: String,
    val folder: String,
    val error: NameError? = null,
    val errorPath: String? = null,
    val checking: Boolean = false,
    val clash: Clash? = null,
    val picker: FolderPickerState? = null,
)

/** The Folder a New note goes to from the main screen: the current Tree Folder, or the Vault root in List mode. */
fun newNoteFolder(route: Route): String = (route as? Route.Tree)?.folder ?: ""

/**
 * State holder of the New note flow (M5 spec §3, §5.2): the title dialog, the server check of the target Folder
 * (ADR 0006), the title-clash dialog, and the Note that opens in Edit mode with the keyboard up. One per main-screen
 * route entry; the Note screen makes one for "Create note" from an unresolved wikilink. A null [state] is closed.
 */
class NewNoteController(
    private val scope: CoroutineScope,
    private val creation: NoteCreation,
    private val navigator: Navigator,
) {
    private val _state = MutableStateFlow<NewNoteState?>(null)
    val state: StateFlow<NewNoteState?> = _state.asStateFlow()

    // The check or creation in flight; a second tap on Create does nothing while it runs.
    private var job: Job? = null

    fun open(
        folder: String,
        title: String = "",
    ) {
        if (job?.isActive == true) return
        _state.value = NewNoteState(title, folder)
    }

    fun onTitle(t: String) = edit { it.copy(title = t, error = null, errorPath = null) }

    fun onFolder(f: String) = edit { it.copy(folder = f, error = null, errorPath = null, picker = null) }

    /** Validates, checks the server, then creates and opens the Note, or shows the clash dialog. */
    fun create() {
        val s = _state.value ?: return
        if (job?.isActive == true || s.clash != null) return
        val title = s.title.trim()
        NameRules.validateTitle(title)?.let { e ->
            _state.value = s.copy(error = e, errorPath = null)
            return
        }
        _state.value = s.copy(title = title, checking = true, error = null, errorPath = null)
        job =
            scope.launch {
                try {
                    val folder =
                        creation.normaliseFolder(s.folder).getOrElse { e ->
                            val error = (e as? InvalidNameException)?.error ?: NameError.DotSegment
                            _state.update { it?.copy(error = error, errorPath = s.folder) }
                            return@launch
                        }
                    when (val check = creation.check(folder, title)) {
                        is Check.Exists -> {
                            val free = creation.freeTitle(folder, title)
                            _state.update { it?.copy(clash = Clash(check.localId, folder, free)) }
                        }

                        Check.Free, Check.Unchecked -> {
                            finish(folder, title)
                        }
                    }
                } finally {
                    _state.update { it?.copy(checking = false) }
                }
            }
    }

    /** The clash dialog's "Open existing". */
    fun openExisting() {
        val clash = _state.value?.clash ?: return
        if (job?.isActive == true) return
        _state.value = null
        navigator.push(Route.Note(clash.localId))
    }

    /** The clash dialog's "Create “Title (2)”": the free title was computed after the refresh, so no second check. */
    fun createFreeTitle() {
        val clash = _state.value?.clash ?: return
        if (job?.isActive == true) return
        _state.update { it?.copy(checking = true) }
        job =
            scope.launch {
                try {
                    finish(clash.folder, clash.freeTitle)
                } finally {
                    _state.update { it?.copy(checking = false) }
                }
            }
    }

    /** Closes everything; a check still running is cancelled (its Folder refresh still lands, in the app scope). */
    fun dismiss() {
        job?.cancel()
        job = null
        _state.value = null
    }

    /** Back: up one level in the picker (closing it at the root), else the clash dialog, else the dialog. */
    fun back() {
        val s = _state.value ?: return
        when {
            s.picker != null -> _state.value = s.copy(picker = s.picker.back())
            s.clash != null -> _state.value = s.copy(clash = null)
            else -> dismiss()
        }
    }

    // ---- the Folder picker (Change) ----

    fun changeFolder() = edit { it.copy(picker = FolderPickerState(it.folder)) }

    fun pickerOpen(path: String) = editPicker { it.open(path) }

    fun pickerNewFolder() = editPicker { it.startNewFolder() }

    fun pickerNewFolderText(text: String) = editPicker { it.onNewFolder(text) }

    fun pickerNewFolderDone() {
        val picker = _state.value?.picker ?: return
        scope.launch {
            val normalised = creation.normaliseFolder(picker.typedPath())
            editPicker { if (it == picker) it.withNewFolder(normalised) else it }
        }
    }

    /** "Choose": the picker's Folder becomes the dialog's. */
    fun pickerConfirm() {
        val picker = _state.value?.picker ?: return
        onFolder(picker.path)
    }

    private suspend fun finish(
        folder: String,
        title: String,
    ) {
        val localId = creation.create(folder, title)
        _state.value = null
        if (localId != null) navigator.push(Route.Note(localId, focus = true))
    }

    private fun edit(change: (NewNoteState) -> NewNoteState) {
        _state.update { s -> if (s == null || s.checking) s else change(s) }
    }

    private fun editPicker(change: (FolderPickerState) -> FolderPickerState) {
        _state.update { s -> s?.picker?.let { s.copy(picker = change(it)) } ?: s }
    }
}
