package app.citti.core.sync

import app.citti.core.api.ApiError
import app.citti.core.api.ApiResult
import app.citti.core.api.DeletePreconditionFailed
import app.citti.core.api.NewNote
import app.citti.core.api.NotesApi
import app.citti.core.api.RemoteNote
import app.citti.core.api.UpdateNote
import app.citti.core.api.UpdateResult
import app.citti.core.api.logText
import app.citti.core.db.CittiDatabase
import app.citti.core.db.Note
import app.citti.core.log.SyncLog

/** A Note whose push failed with an error that does not stop the run. */
data class PushFailure(
    val localId: Long,
    val error: ApiError,
)

sealed interface PushResult {
    /**
     * The run reached the end. [conflicts] are the local ids of the Conflict copies created.
     * Notes in [failures] stay in the queue.
     */
    data class Done(
        val updated: Int = 0,
        val created: Int = 0,
        val deleted: Int = 0,
        val conflicts: List<Long> = emptyList(),
        val skippedReadOnly: Int = 0,
        val failures: List<PushFailure> = emptyList(),
        /** Local deletes undone because the server Note changed since the last sync (server wins). */
        val deleteUndone: Int = 0,
    ) : PushResult

    /** Auth, server or network failure: the run stopped and the queue is intact. */
    data class Failed(
        val error: ApiError,
    ) : PushResult
}

/**
 * Pushes the queue of local changes (ADR 0003: on a 412 the server wins and the local text becomes a Conflict copy).
 * A delete never removes a server version this client has not seen: the server version wins and the Note comes back.
 * With [verifyBeforeDelete] a `GET` compares ETags first, on top of `If-Match` (Nextcloud ignores `If-Match` on
 * `DELETE`, so only the `GET` guards it, with a short race window before the `DELETE`). [now] returns epoch seconds.
 */
class PushSync(
    private val api: NotesApi,
    db: CittiDatabase,
    private val log: SyncLog = SyncLog.None,
    private val now: () -> Long,
) {
    /** See the class comment. Keep it on: Nextcloud ignores `If-Match` on `DELETE` (`PushRealServerTest` scenario 7). */
    var verifyBeforeDelete: Boolean = true

    private val queries = db.noteQueries
    private val database = db

    private var updated = 0
    private var created = 0
    private var deleted = 0
    private var deleteUndone = 0
    private val conflicts = mutableListOf<Long>()
    private val skipped = mutableSetOf<Long>()
    private val failures = mutableListOf<PushFailure>()

    /** Not safe to call concurrently; the sync engine runs one sync at a time. */
    suspend fun push(): PushResult {
        updated = 0
        created = 0
        deleted = 0
        deleteUndone = 0
        conflicts.clear()
        skipped.clear()
        failures.clear()

        val attempted = mutableSetOf<Long>()
        repeat(MAX_PASSES) {
            val batch = queries.selectDirty().executeAsList().filter { it.local_id !in attempted }
            if (batch.isEmpty()) return done()
            for (note in batch) {
                attempted += note.local_id
                // A Conflict copy only exists after a pass; stop the whole run on a fatal error.
                pushOne(note)?.let { return PushResult.Failed(it) }
            }
        }
        return done()
    }

    private fun done() = PushResult.Done(updated, created, deleted, conflicts.toList(), skipped.size, failures.toList(), deleteUndone)

    /** Returns a run-stopping error, or null. */
    private suspend fun pushOne(note: Note): ApiError? {
        if (note.readonly) {
            skipped += note.local_id
            return null
        }
        return when (note.sync_state) {
            "DELETED" -> pushDelete(note)
            "NEW" -> pushCreate(note)
            else -> if (note.remote_id == null) pushCreate(note) else pushUpdate(note, note.remote_id)
        }
    }

    private suspend fun pushCreate(note: Note): ApiError? {
        val body = NewNote(note.title, note.content.orEmpty(), note.category, note.favorite)
        return when (val result = api.createNote(body)) {
            is ApiResult.Success -> {
                markSynced(note.local_id, result.value, body.title, body.category, body.content)
                created++
                null
            }

            is ApiResult.Failure -> {
                handle(note, result.error)
            }
        }
    }

    private suspend fun pushDelete(note: Note): ApiError? {
        val remoteId = note.remote_id
        if (remoteId == null) {
            queries.deleteByLocalId(note.local_id)
            return null
        }
        if (verifyBeforeDelete) {
            when (val check = api.getNote(remoteId)) {
                is ApiResult.Success -> {
                    if (check.value.etag != note.etag) return undoDelete(note, check.value)
                }

                is ApiResult.Failure -> {
                    // NotFound: already gone, the delete is done.
                    return if (check.error == ApiError.NotFound) deleteLocal(note) else handle(note, check.error)
                }
            }
        }
        return when (val result = api.deleteNote(remoteId, ifMatch = note.etag?.let(::quote))) {
            is ApiResult.Success -> {
                if (result.value is DeletePreconditionFailed) undoDeleteFromServer(note, remoteId) else deleteLocal(note)
            }

            is ApiResult.Failure -> {
                handle(note, result.error)
            }
        }
    }

    private fun deleteLocal(note: Note): ApiError? {
        queries.deleteByLocalId(note.local_id)
        deleted++
        return null
    }

    /** The delete answered 412: fetch the server version and keep it. */
    private suspend fun undoDeleteFromServer(
        note: Note,
        remoteId: Long,
    ): ApiError? =
        when (val result = api.getNote(remoteId)) {
            is ApiResult.Success -> undoDelete(note, result.value)
            is ApiResult.Failure -> if (result.error == ApiError.NotFound) deleteLocal(note) else handle(note, result.error)
        }

    /** Server wins over a local delete: the Note comes back with the server text. */
    private fun undoDelete(
        note: Note,
        server: RemoteNote,
    ): ApiError? {
        storeServerVersion(note, server)
        deleteUndone++
        log.warn("push delete undone: server Note changed local=${note.local_id}")
        return null
    }

    private suspend fun pushUpdate(
        note: Note,
        remoteId: Long,
    ): ApiError? {
        val body = UpdateNote(note.title, note.content.orEmpty(), note.category, note.favorite)
        return when (val result = api.updateNote(remoteId, body, ifMatch = quote(note.etag.orEmpty()))) {
            is ApiResult.Success -> {
                when (val update = result.value) {
                    is UpdateResult.Updated -> {
                        markSynced(note.local_id, update.note, note.title, note.category, note.content.orEmpty())
                        updated++
                        null
                    }

                    UpdateResult.PreconditionFailed -> {
                        resolveConflict(note, remoteId)
                    }
                }
            }

            is ApiResult.Failure -> {
                if (result.error == ApiError.NotFound) recreate(note) else handle(note, result.error)
            }
        }
    }

    /** The server Note is gone: keep the local text as a new Note and push it now. */
    private suspend fun recreate(note: Note): ApiError? {
        queries.resetToNew(note.local_id)
        return pushCreate(queries.selectByLocalId(note.local_id).executeAsOne())
    }

    /**
     * Server wins: its version replaces the Note in place, the local text moves to a Conflict copy. The row is read
     * again inside the transaction, so the copy holds text typed while the server version was fetched. A Note deleted
     * meanwhile takes the server version without a copy.
     */
    private suspend fun resolveConflict(
        note: Note,
        remoteId: Long,
    ): ApiError? =
        when (val result = api.getNote(remoteId)) {
            is ApiResult.Success -> {
                val server = result.value
                database.transaction {
                    val current = queries.selectByLocalId(note.local_id).executeAsOneOrNull() ?: return@transaction
                    val sameText = server.content.orEmpty() == current.content.orEmpty() && server.title == current.title
                    if (!sameText && current.sync_state != "DELETED") {
                        val title = freeConflictTitle(current.category, "${current.title} (conflict ${isoDate(now())})")
                        queries.insertNew(title, current.category, current.content.orEmpty(), now(), current.local_id)
                        conflicts += queries.lastInsertedLocalId().executeAsOne()
                        log.warn("push conflict: server wins, local text kept as copy local=${conflicts.last()}")
                    }
                    storeServerVersion(current, server)
                }
                null
            }

            is ApiResult.Failure -> {
                // NotFound: deleted on the server meanwhile.
                if (result.error == ApiError.NotFound) recreate(note) else handle(note, result.error)
            }
        }

    private fun storeServerVersion(
        note: Note,
        server: RemoteNote,
    ) = queries.replaceWithServer(
        title = server.title ?: note.title,
        category = server.category ?: note.category,
        content = server.content,
        favorite = server.favorite ?: note.favorite,
        etag = server.etag,
        modified = server.modified ?: now(),
        readonly = server.readonly ?: false,
        localId = note.local_id,
    )

    private fun freeConflictTitle(
        category: String,
        base: String,
    ): String {
        var title = base
        var n = 2
        while (queries.folderHasTitle(category, title).executeAsOne() > 0) title = "$base $n".also { n++ }
        return title
    }

    /** [sentTitle], [sentCategory] and [sentContent] are the values the request carried. */
    private fun markSynced(
        localId: Long,
        remote: RemoteNote,
        sentTitle: String,
        sentCategory: String,
        sentContent: String,
    ) = queries.markSynced(
        remoteId = remote.id,
        etag = remote.etag,
        sentTitle = sentTitle,
        title = remote.title ?: sentTitle,
        sentCategory = sentCategory,
        category = remote.category ?: sentCategory,
        sentContent = sentContent,
        modified = remote.modified ?: now(),
        localId = localId,
    )

    /** Fatal errors stop the run, anything else is recorded and the next Note goes on. */
    private fun handle(
        note: Note,
        error: ApiError,
    ): ApiError? =
        when (error) {
            is ApiError.Auth, is ApiError.Server, is ApiError.Network -> {
                error
            }

            else -> {
                failures += PushFailure(note.local_id, error)
                log.warn("push note failed local=${note.local_id} ${error.logText()}")
                null
            }
        }

    private fun quote(etag: String) = if (etag.startsWith("\"") || etag.startsWith("W/")) etag else "\"$etag\""

    private companion object {
        const val MAX_PASSES = 5
    }
}

/** `YYYY-MM-DD` (UTC) for epoch seconds, without a platform date API. */
internal fun isoDate(epochSeconds: Long): String {
    val z = epochSeconds.floorDiv(86_400L) + 719_468
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + (if (m <= 2) 1 else 0)
    return "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}
