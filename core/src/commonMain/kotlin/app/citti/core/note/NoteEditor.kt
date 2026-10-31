package app.citti.core.note

import app.citti.core.db.CittiDatabase
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
)

/**
 * The only writer of a Note's text from the UI. One per open Note. An edit names the etag its text was based on
 * (the edit base): if a pull brought a newer server version meanwhile, the write is refused and the text becomes a
 * Conflict copy (ADR 0003), so a server change is never saved over unseen.
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
        ) : SaveResult

        /** The Note is gone or deleted; [copyLocalId] holds the text (`conflict_of` NULL). */
        data class Gone(
            val copyLocalId: Long,
        ) : SaveResult

        data object ReadOnly : SaveResult
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
        )
    }

    /** Saves [text], based on [baseEtag]. A text equal to the last saved one is a no-op. */
    fun save(
        text: String,
        baseEtag: String?,
    ): SaveResult =
        db.transactionWithResult {
            val row = queries.selectByLocalId(localId).executeAsOneOrNull()
            when {
                // Deleted meanwhile (on the server, or by the user): keep the text, unlinked, in the Folder it was in.
                row == null || row.sync_state == "DELETED" -> {
                    SaveResult.Gone(copies.create(title, folder, text, null))
                }

                row.readonly -> {
                    SaveResult.ReadOnly
                }

                text == lastSaved -> {
                    SaveResult.Saved
                }

                else -> {
                    val changed = queries.saveEdit(text, excerptOf(text, row.title), clock(), localId, baseEtag).value
                    if (changed > 0) {
                        lastSaved = text
                        afterEdit()
                        SaveResult.Saved
                    } else {
                        SaveResult.Conflict(copies.create(row.title, row.category, text, localId))
                    }
                }
            }
        }
}
