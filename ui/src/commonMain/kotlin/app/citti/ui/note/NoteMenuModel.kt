package app.citti.ui.note

import app.citti.core.note.NameError
import app.citti.core.note.NameRules
import app.citti.core.note.NoteInfo
import app.citti.core.note.RenameResult
import app.citti.core.settings.ExclusionRepository
import app.citti.ui.components.FolderPickerState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import app.citti.core.note.NoteActions as CoreNoteActions

/**
 * The dialog or sheet of the ⋮ menu shown over the Note screen. It lives in the controller so it survives
 * recomposition (not process death).
 */
sealed interface NoteDialog {
    /** Rename…: [text] is the typed title; [error] or [clashFolder] is shown under the field. */
    data class Rename(
        val text: String,
        val error: NameError? = null,
        /** The Folder that already has a Note named [text]. */
        val clashFolder: String? = null,
        val checking: Boolean = false,
    ) : NoteDialog

    /** Move…: the Folder picker; [error] or [clashFolder] is shown above "Move here". */
    data class Move(
        val picker: FolderPickerState,
        val error: NameError? = null,
        /** The path the [error] is about (the Unsynced folder message names it). */
        val errorPath: String = "",
        val clashFolder: String? = null,
        val checking: Boolean = false,
    ) : NoteDialog

    data object DeleteConfirm : NoteDialog

    data class Info(
        val info: NoteInfo,
    ) : NoteDialog
}

/**
 * What the ⋮ menu of one Note needs from the data layer (`NoteActions`), so [NoteController] is tested without a
 * database. The calls touch the database and run off the main thread in the implementation.
 */
interface NoteMenuGateway {
    /** False when the Note is gone, deleted or read-only. */
    suspend fun setFavorite(favorite: Boolean): Boolean

    suspend fun rename(title: String): RenameResult

    suspend fun move(folder: String): RenameResult

    /** False when the Note is gone, already deleted or read-only. */
    suspend fun delete(): Boolean

    suspend fun info(): NoteInfo?

    /** `NameRules.normaliseFolder` against the current Unsynced folders (the picker's "New folder…"). */
    suspend fun normaliseFolder(path: String): Result<String>
}

/** [NoteMenuGateway] over the session's [CoreNoteActions] (null while signed out), with every call on [io]. */
class CoreNoteMenu(
    private val localId: Long,
    private val actions: () -> CoreNoteActions?,
    private val exclusions: ExclusionRepository,
    private val io: CoroutineDispatcher,
) : NoteMenuGateway {
    override suspend fun setFavorite(favorite: Boolean): Boolean =
        actions()?.let { withContext(io) { it.setFavorite(localId, favorite) } } ?: false

    override suspend fun rename(title: String): RenameResult =
        actions()?.let { withContext(io) { it.rename(localId, title) } } ?: RenameResult.Gone

    override suspend fun move(folder: String): RenameResult =
        actions()?.let { withContext(io) { it.move(localId, folder) } } ?: RenameResult.Gone

    override suspend fun delete(): Boolean = actions()?.let { withContext(io) { it.delete(localId) } } ?: false

    override suspend fun info(): NoteInfo? = actions()?.let { withContext(io) { it.info(localId) } }

    override suspend fun normaliseFolder(path: String): Result<String> = withContext(io) { NameRules.normaliseFolder(path, exclusions) }
}
