package app.citti.core.note

import app.citti.core.db.CittiDatabase
import app.citti.core.markdown.TextOps
import app.citti.core.markdown.apply
import app.citti.core.sync.ConflictCopies

/** The Note as the editor opens it. [text] is null while the content is not downloaded yet. */
data class EditorDoc(
    val text: String?,
    val baseEtag: String?,
    val title: String,
    val folder: String,
    val readonly: Boolean,
    val badge: NoteBadge?,
    val conflictCopy: Pair<Long, String>?,
    /** True while the Note has changes the server does not have (DIRTY, NEW). */
    val pending: Boolean = false,
    /** True when this Note is itself a Conflict copy; then [conflictCopy] is its original. */
    val isConflictCopy: Boolean = false,
    val favorite: Boolean = false,
)

/**
 * The only writer of a Note's text from the UI. One per open Note. An edit names the etag its text was based on
 * (the edit base) and the text it was based on: if a pull brought a newer server version meanwhile, the write is
 * refused and the text becomes a Conflict copy (ADR 0003), so a server change is never saved over unseen. A row that
 * still holds the base text has no server change to lose (our own push only changes the etag), so it is written.
 */
class NoteEditor(
    private val db: CittiDatabase,
    private val localId: Long,
    private val clock: () -> Long,
    private val afterEdit: () -> Unit = {},
) {
    sealed interface SaveResult {
        data object Saved : SaveResult

        /** The edit was based on a stale server version; [copyLocalId] holds the text. The row is unchanged. */
        data class Conflict(
            val copyLocalId: Long,
            val copyTitle: String,
        ) : SaveResult

        /** The Note is gone or deleted; [copyLocalId] holds the text (`conflict_of` NULL). */
        data class Gone(
            val copyLocalId: Long,
            val copyTitle: String,
        ) : SaveResult

        data object ReadOnly : SaveResult

        /** [toggleTask] found something else than the expected checkbox, or [insertAt] found no text; nothing was written. */
        data object Mismatch : SaveResult
    }

    private val queries = db.noteQueries
    private val copies = ConflictCopies(db, clock)
    private var lastSaved: String? = null
    private var title = ""
    private var folder = ""

    fun load(): EditorDoc? {
        val row = queries.selectForEditor(localId).executeAsOneOrNull() ?: return null
        if (row.sync_state == "DELETED") return null
        lastSaved = row.content
        title = row.title
        folder = row.category
        val copy = queries.selectConflictCopyOf(localId).executeAsOneOrNull()
        val original = row.conflict_of?.let { queries.selectByLocalId(it).executeAsOneOrNull() }
        return EditorDoc(
            text = row.content,
            baseEtag = row.etag,
            title = row.title,
            folder = row.category,
            readonly = row.readonly,
            badge =
                if (copy != null ||
                    original != null
                ) {
                    NoteBadge.Conflict
                } else if (row.sync_state != "SYNCED") {
                    NoteBadge.Pending
                } else {
                    null
                },
            conflictCopy = copy?.let { it.local_id to it.title } ?: original?.let { it.local_id to it.title },
            pending = row.sync_state != "SYNCED",
            isConflictCopy = original != null,
            favorite = row.favorite,
        )
    }

    /** The text last read from or written to the row; what [save] compares with to skip a no-op. */
    val savedText: String? get() = lastSaved

    /** Saves [text], based on [baseEtag]. A text equal to the last saved one is a no-op. */
    fun save(
        text: String,
        baseEtag: String?,
        baseText: String? = null,
    ): SaveResult = db.transactionWithResult { write(text, baseEtag, baseText) }

    /**
     * Flips the checkbox whose `[` is at [box] (`Task.box`): re-reads the row's text, checks that the three characters
     * there still equal [expected] (`[ ]` or `[x]`), swaps only the inner one and saves like [save]. Anything else
     * there writes nothing and gives [SaveResult.Mismatch]. On [SaveResult.Saved] the new text is [savedText].
     */
    fun toggleTask(
        box: Int,
        expected: String,
        baseEtag: String?,
        baseText: String? = null,
    ): SaveResult =
        db.transactionWithResult {
            val current =
                queries
                    .selectByLocalId(localId)
                    .executeAsOneOrNull()
                    ?.takeIf { it.sync_state != "DELETED" }
                    ?.content
            if (current == null || box < 0 || box + 3 > current.length || current.substring(box, box + 3) != expected) {
                SaveResult.Mismatch
            } else {
                val inner = if (expected[1] == ' ') 'x' else ' '
                write(current.substring(0, box + 1) + inner + current.substring(box + 2), baseEtag, baseText)
            }
        }

    /**
     * Puts [block] on its own line at [offset] of the row's text, as [TextOps.insertBlock] does ([offset] clamped to
     * the text), and saves like [save]: for an Image link whose upload ended after its Note's screen was left. On
     * [SaveResult.Saved] the new text is [savedText]. A Note gone meanwhile keeps the last text with the block in a
     * copy ([SaveResult.Gone]); a row with no text yet gives [SaveResult.Mismatch].
     */
    fun insertAt(
        offset: Int,
        block: String,
        baseEtag: String?,
        baseText: String? = null,
    ): SaveResult =
        db.transactionWithResult {
            val row = queries.selectByLocalId(localId).executeAsOneOrNull()
            // Gone meanwhile: the copy write() makes holds the last text with the block.
            val current = if (row == null || row.sync_state == "DELETED") lastSaved.orEmpty() else row.content
            if (current == null) {
                SaveResult.Mismatch
            } else {
                write(current.apply(TextOps.insertBlock(current, offset, block)), baseEtag, baseText)
            }
        }

    private fun write(
        text: String,
        baseEtag: String?,
        baseText: String?,
    ): SaveResult {
        val row = queries.selectByLocalId(localId).executeAsOneOrNull()
        return when {
            // Deleted meanwhile (on the server, or by the user): keep the text, unlinked, in the Folder it was in.
            row == null || row.sync_state == "DELETED" -> {
                copy(title, folder, text, null) { id, name -> SaveResult.Gone(id, name) }
            }

            row.readonly -> {
                SaveResult.ReadOnly
            }

            text == lastSaved -> {
                SaveResult.Saved
            }

            else -> {
                val changed = queries.saveEdit(text, excerptOf(text, row.title), clock(), localId, baseEtag, baseText).value
                if (changed > 0) {
                    lastSaved = text
                    afterEdit()
                    SaveResult.Saved
                } else {
                    copy(row.title, row.category, text, localId) { id, name -> SaveResult.Conflict(id, name) }
                }
            }
        }
    }

    private fun copy(
        title: String,
        category: String,
        text: String,
        conflictOf: Long?,
        result: (Long, String) -> SaveResult,
    ): SaveResult {
        val id = copies.create(title, category, text, conflictOf)
        return result(id, queries.selectByLocalId(id).executeAsOne().title)
    }
}
