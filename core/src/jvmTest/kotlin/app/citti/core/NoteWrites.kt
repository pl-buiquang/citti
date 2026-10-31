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

/** Reads the current title of [localId] to compute the excerpt; markDirty does not change the title. */
fun NoteQueries.markDirty(
    content: String?,
    modified: Long,
    localId: Long,
) = markDirty(content, excerptOf(content, titleOf(localId)), modified, localId)

fun NoteQueries.updateContent(
    content: String?,
    etag: String?,
    localId: Long,
) = updateContent(content, excerptOf(content, titleOf(localId)), etag, localId)

private fun NoteQueries.titleOf(localId: Long) = selectByLocalId(localId).executeAsOneOrNull()?.title.orEmpty()
