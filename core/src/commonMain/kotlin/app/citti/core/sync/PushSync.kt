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
import app.citti.core.note.excerptOf
import app.citti.core.settings.ExclusionRepository

/**
 * An untouched New note the push deleted because the server already had its folder and title (ADR 0006). The
 * server's Note is [serverLocalId].
 */
data class Dropped(
    val localId: Long,
    val serverLocalId: Long,
    val title: String,
    val category: String,
)

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
        /** Untouched New notes deleted because the server already had their title (push-time check). */
        val dropped: List<Dropped> = emptyList(),
        /** New notes not pushed in this run because their folder could not be refreshed. */
        val deferredNew: Int = 0,
    ) : PushResult

    /** Auth, server or network failure: the run stopped and the rest of the queue is intact. */
    data class Failed(
        val error: ApiError,
        /** New notes dropped before the run stopped. */
        val dropped: List<Dropped> = emptyList(),
    ) : PushResult
}

/**
 * Pushes the queue of local changes (ADR 0003: on a 412 the server wins and the local text becomes a Conflict copy).
 * A delete never removes a server version this client has not seen: the server version wins and the Note comes back.
 * With [verifyBeforeDelete] a `GET` compares ETags first, on top of `If-Match` (Nextcloud ignores `If-Match` on
 * `DELETE`, so only the `GET` guards it, with a short race window before the `DELETE`). [now] returns epoch seconds.
 *
 * Push-time check (ADR 0006): before a never-pushed New note is created, its folder is refreshed once per run
 * ([FolderSync]). When the server already has that folder and title, the server wins: an untouched Note (its text
 * still equals `created_from`) is dropped, any other becomes a Conflict copy of the server Note and is pushed under
 * that title. The server never refuses a duplicate, it would create `Title (2)`, so a folder that cannot be
 * refreshed has its New notes kept for a later run instead of created blind.
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
    private val conflictCopies = ConflictCopies(db, now)
    private val folderSync = FolderSync(db, api, ExclusionRepository(db), log)
    private val database = db

    private var updated = 0
    private var created = 0
    private var deleted = 0
    private var deleteUndone = 0
    private val conflicts = mutableListOf<Long>()
    private val skipped = mutableSetOf<Long>()
    private val failures = mutableListOf<PushFailure>()
    private val dropped = mutableListOf<Dropped>()

    /** Folders refreshed in this run: true when refreshed, false when blocked (refresh failed). */
    private val checkedFolders = mutableMapOf<String, Boolean>()
    private val deferred = mutableSetOf<Long>()

    /** The first folder refresh failure of the run, and whether any folder refresh went through. */
    private var refreshError: ApiError? = null
    private var refreshed = false

    /** Not safe to call concurrently; the sync engine runs one sync at a time. */
    suspend fun push(): PushResult {
        updated = 0
        created = 0
        deleted = 0
        deleteUndone = 0
        conflicts.clear()
        skipped.clear()
        failures.clear()
        dropped.clear()
        checkedFolders.clear()
        deferred.clear()
        refreshError = null
        refreshed = false

        val attempted = mutableSetOf<Long>()
        repeat(MAX_PASSES) {
            // Conflict copies made by a pass are New notes too: each pass checks the New notes it is about to push.
            checkNewNotes(queries.selectDirty().executeAsList().filter { it.local_id !in attempted })
                ?.let { return PushResult.Failed(it, dropped.toList()) }
            val batch = queries.selectDirty().executeAsList().filter { it.local_id !in attempted }
            if (batch.isEmpty()) return done()
            for (note in batch) {
                attempted += note.local_id
                if (note.isNeverPushed() && checkedFolders[note.category] == false) {
                    deferred += note.local_id
                    continue
                }
                // A Conflict copy only exists after a pass; stop the whole run on a fatal error.
                pushOne(note)?.let { return PushResult.Failed(it, dropped.toList()) }
            }
        }
        return done()
    }

    /**
     * When New notes were held back and the server was never reached in this run (offline), the run fails with the
     * refresh error, so the status says Offline instead of Synced.
     */
    private fun done(): PushResult {
        val error = refreshError
        if (error != null && deferred.isNotEmpty() && !refreshed && updated + created + deleted + deleteUndone == 0 &&
            conflicts.isEmpty()
        ) {
            return PushResult.Failed(error, dropped.toList())
        }
        return PushResult.Done(
            updated,
            created,
            deleted,
            conflicts.toList(),
            skipped.size,
            failures.toList(),
            deleteUndone,
            dropped.toList(),
            deferred.size,
        )
    }

    private fun Note.isNeverPushed() = sync_state == "NEW" && remote_id == null && !readonly

    /**
     * Refreshes the folder of each never-pushed New note in [batch] (once per run), then drops or converts the ones
     * whose folder and title the server has (exact match, like the server). The caller reads the queue again.
     * Returns an auth error, which stops the run; any other refresh failure only holds back that folder.
     */
    private suspend fun checkNewNotes(batch: List<Note>): ApiError? {
        val fresh = batch.filter { it.isNeverPushed() }
        for (folder in fresh.map { it.category }.distinct()) {
            if (folder in checkedFolders) continue
            checkedFolders[folder] =
                when (val refresh = folderSync.run(folder)) {
                    FolderRefresh.NotModified, is FolderRefresh.Refreshed -> {
                        refreshed = true
                        true
                    }

                    // Creation refuses Unsynced folders; never push blind into one.
                    FolderRefresh.Unsynced -> {
                        false
                    }

                    is FolderRefresh.Failed -> {
                        if (refresh.error is ApiError.Auth) return refresh.error
                        if (refreshError == null) refreshError = refresh.error
                        log.warn("push folder refresh failed, its new notes wait ${refresh.error.logText()}")
                        false
                    }
                }
        }
        for (note in fresh) {
            if (checkedFolders[note.category] != true) continue
            val server = queries.selectServerNote(note.category, note.title).executeAsOneOrNull() ?: continue
            if (queries.dropIfUntouched(note.local_id).value > 0) {
                dropped += Dropped(note.local_id, server.local_id, note.title, note.category)
                log.info("push dropped untouched new note local=${note.local_id} server=${server.local_id}")
            } else {
                // Edited (maybe while the folder was refreshed): read it again, the copy title is computed from it.
                val current = queries.selectByLocalId(note.local_id).executeAsOneOrNull() ?: continue
                if (conflictCopies.convert(current, server.local_id)) {
                    conflicts += note.local_id
                    log.warn("push new note exists on the server, kept as copy local=${note.local_id} server=${server.local_id}")
                }
            }
        }
        return null
    }

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
                markSynced(note.local_id, result.value, body.title, body.category, body.content, body.favorite)
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
                        markSynced(note.local_id, update.note, note.title, note.category, note.content.orEmpty(), note.favorite)
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
                        conflicts += conflictCopies.create(current.title, current.category, current.content.orEmpty(), current.local_id)
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
    ) {
        val title = server.title ?: note.title
        queries.replaceWithServer(
            title = title,
            category = server.category ?: note.category,
            content = server.content,
            excerpt = excerptOf(server.content, title),
            favorite = server.favorite ?: note.favorite,
            etag = server.etag,
            modified = server.modified ?: now(),
            readonly = server.readonly ?: false,
            localId = note.local_id,
        )
    }

    /** [sentTitle], [sentCategory], [sentContent] and [sentFavorite] are the values the request carried. */
    private fun markSynced(
        localId: Long,
        remote: RemoteNote,
        sentTitle: String,
        sentCategory: String,
        sentContent: String,
        sentFavorite: Boolean,
    ) = queries.markSynced(
        remoteId = remote.id,
        etag = remote.etag,
        sentTitle = sentTitle,
        title = remote.title ?: sentTitle,
        sentCategory = sentCategory,
        category = remote.category ?: sentCategory,
        sentContent = sentContent,
        sentFavorite = sentFavorite,
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
