package app.citti.core.sync

import app.citti.core.db.CittiDatabase
import app.citti.core.db.Note

/** The queue of local changes not yet on the server. It is plain DB state, so it survives restarts. */
class PendingChanges(
    db: CittiDatabase,
) {
    private val queries = db.noteQueries

    /** Notes that are DIRTY, NEW or DELETED, oldest first. */
    fun list(): List<Note> = queries.selectDirty().executeAsList()

    fun count(): Int = list().size
}
