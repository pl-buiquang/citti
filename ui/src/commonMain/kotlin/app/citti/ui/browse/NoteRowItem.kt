package app.citti.ui.browse

import app.citti.core.db.SelectFolderNotes
import app.citti.core.db.SelectListRows
import app.citti.core.note.noteBadgeOf
import app.citti.ui.components.RowBadge
import app.citti.ui.components.toRowBadge

/**
 * One Note row of List, Tree and Search. [folder] is "" for a Note at the Vault root; [modified] is the
 * server's epoch seconds. [hidden] and [snippet] (with `\u0002`/`\u0003` markers) are only set in Search.
 */
data class NoteRowItem(
    val localId: Long,
    val title: String,
    val folder: String,
    val excerpt: String?,
    val favorite: Boolean,
    val modified: Long,
    val badge: RowBadge?,
    val hidden: Boolean = false,
    val snippet: String? = null,
)

/** A Conflict copy shows "Conflict"; any other unsynced Note shows "Pending". */
fun noteRowItem(
    localId: Long,
    title: String,
    folder: String,
    excerpt: String?,
    favorite: Boolean,
    modified: Long,
    syncState: String,
    conflictOf: Long?,
): NoteRowItem =
    NoteRowItem(
        localId = localId,
        title = title,
        folder = folder,
        excerpt = excerpt,
        favorite = favorite,
        modified = modified,
        badge = noteBadgeOf(conflictOf, syncState)?.toRowBadge(),
    )

fun SelectListRows.toRowItem(): NoteRowItem = noteRowItem(local_id, title, category, excerpt, favorite, modified, sync_state, conflict_of)

fun SelectFolderNotes.toRowItem(): NoteRowItem =
    noteRowItem(local_id, title, category, excerpt, favorite, modified, sync_state, conflict_of)
