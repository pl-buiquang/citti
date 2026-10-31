package app.citti.core

import app.citti.core.db.NoteQueries
import app.citti.core.note.excerptOf

// Test overloads of the writes that set content: they compute the excerpt the way the sync code does,
// so tests pass the same arguments as before the excerpt column existed.

fun NoteQueries.insertNew(
    title: String,
    category: String,
    content: String?,
    modified: Long,
    conflictOf: Long?,
) = insertNew(title, category, content, excerptOf(content, title), modified, conflictOf)

/** A local edit as the editor makes it: based on the row's current etag, so it is not refused for that. */
fun NoteQueries.markDirty(
    content: String?,
    modified: Long,
    localId: Long,
) {
    // A Note whose text was never downloaded has no text to edit; give it an empty one first.
    if (selectByLocalId(localId).executeAsOne().content == null) updateContent("", null, localId)
    val row = selectByLocalId(localId).executeAsOne()
    saveEdit(content, excerptOf(content, row.title), modified, localId, row.etag)
}

fun NoteQueries.updateContent(
    content: String?,
    etag: String?,
    localId: Long,
) = updateContent(content, excerptOf(content, titleOf(localId)), etag, localId)

private fun NoteQueries.titleOf(localId: Long) = selectByLocalId(localId).executeAsOneOrNull()?.title.orEmpty()
