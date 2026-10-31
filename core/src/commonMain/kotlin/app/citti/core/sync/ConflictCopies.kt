package app.citti.core.sync

import app.citti.core.db.CittiDatabase
import app.citti.core.db.Note
import app.citti.core.note.excerptOf

/**
 * Creates Conflict copies (ADR 0003): a new Note in the same Folder, titled `T (conflict YYYY-MM-DD)`, that holds
 * the local text. Used by the push (server wins) and by the editor (an edit based on a stale server version).
 * [now] returns epoch seconds. Call it inside a transaction when the copy must go with other writes.
 */
class ConflictCopies(
    db: CittiDatabase,
    private val now: () -> Long,
) {
    private val queries = db.noteQueries

    /** Inserts the copy of the Note [title] in [category] holding [text] and returns its local id. */
    fun create(
        title: String,
        category: String,
        text: String,
        conflictOf: Long?,
    ): Long {
        val copyTitle = freeConflictTitle(category, title)
        // The Excerpt skips a heading equal to the original title, which the text still starts with.
        queries.insertNew(copyTitle, category, text, excerptOf(text, title), now(), conflictOf, null)
        return queries.lastInsertedLocalId().executeAsOne()
    }

    /**
     * Turns the never-pushed [row] into a Conflict copy of the server Note [serverLocalId], which has the same folder
     * and title (push-time check, ADR 0006): it gets the copy title, `conflict_of` and no `created_from`, and stays
     * NEW. Returns false when the row is no longer a never-pushed NEW row.
     */
    fun convert(
        row: Note,
        serverLocalId: Long,
    ): Boolean = queries.convertToConflictCopy(freeConflictTitle(row.category, row.title), serverLocalId, row.local_id).value > 0

    private fun freeConflictTitle(
        category: String,
        title: String,
    ) = freeTitle(category, "$title (conflict ${isoDate(now())})")

    private fun freeTitle(
        category: String,
        base: String,
    ): String {
        var title = base
        var n = 2
        while (queries.folderHasTitle(category, title).executeAsOne() > 0) title = "$base $n".also { n++ }
        return title
    }
}
