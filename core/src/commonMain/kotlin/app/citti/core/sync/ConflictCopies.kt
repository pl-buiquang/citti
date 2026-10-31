package app.citti.core.sync

import app.citti.core.db.CittiDatabase
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
        val copyTitle = freeTitle(category, "$title (conflict ${isoDate(now())})")
        // The Excerpt skips a heading equal to the original title, which the text still starts with.
        queries.insertNew(copyTitle, category, text, excerptOf(text, title), now(), conflictOf)
        return queries.lastInsertedLocalId().executeAsOne()
    }

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
