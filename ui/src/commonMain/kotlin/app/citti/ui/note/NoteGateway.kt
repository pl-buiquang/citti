package app.citti.ui.note

import app.citti.core.note.EditorDoc
import app.citti.core.note.LinkTarget
import app.citti.core.note.NoteEditor
import kotlinx.coroutines.flow.Flow

/**
 * What [NoteController] needs from the data layer for one Note: the row as a flow, the editor writes and the link
 * lookups. The functions that touch the database suspend and run off the main thread in the implementation, so the
 * controller stays testable with a fake.
 */
interface NoteGateway {
    /** The row of the Note as the editor sees it, again after every change; null once it is deleted or gone. */
    fun observe(): Flow<EditorDoc?>

    suspend fun save(
        text: String,
        baseEtag: String?,
    ): NoteEditor.SaveResult

    /** `NoteEditor.toggleTask`; on `Saved`, [savedText] is the new text. */
    suspend fun toggleTask(
        box: Int,
        expected: String,
        baseEtag: String?,
    ): NoteEditor.SaveResult

    /** `NoteEditor.insertAt`: [block] on its own line at [offset] of the stored text; on `Saved`, [savedText] is the new text. */
    suspend fun insertAt(
        offset: Int,
        block: String,
        baseEtag: String?,
    ): NoteEditor.SaveResult

    /**
     * Downloads the text of a row that has none yet (a Note a Folder refresh brought), so it opens without waiting
     * for the next Sync. False when it could not.
     */
    suspend fun fetchText(): Boolean = false

    /** The text last written to the row. */
    fun savedText(): String?

    /** [folder] is the Folder of the Note holding the link. */
    suspend fun resolve(
        folder: String,
        ref: LinkRef,
    ): LinkTarget
}
