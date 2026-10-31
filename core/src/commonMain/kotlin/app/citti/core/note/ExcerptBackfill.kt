package app.citti.core.note

import app.citti.core.db.CittiDatabase

/**
 * Computes the Excerpt of every downloaded Note that has none: the whole Vault right after the migration
 * that added the column, normally nothing after that. Runs in one transaction; call it off the main thread.
 */
object ExcerptBackfill {
    /** Returns the number of Notes updated. */
    fun run(db: CittiDatabase): Int =
        db.transactionWithResult {
            val missing = db.noteQueries.selectMissingExcerpt().executeAsList()
            missing.forEach { db.noteQueries.setExcerpt(excerptOf(it.content, it.title), it.local_id) }
            missing.size
        }
}
