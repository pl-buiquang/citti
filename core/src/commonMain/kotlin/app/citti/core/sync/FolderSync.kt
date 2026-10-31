package app.citti.core.sync

import app.citti.core.api.ApiError
import app.citti.core.api.ApiResult
import app.citti.core.api.ListResult
import app.citti.core.api.NotesApi
import app.citti.core.api.RemoteNote
import app.citti.core.api.logText
import app.citti.core.db.CittiDatabase
import app.citti.core.log.SyncLog
import app.citti.core.note.excerptOf
import app.citti.core.settings.ExclusionKind
import app.citti.core.settings.ExclusionRepository

/** What a [FolderSync.run] did. */
sealed interface FolderRefresh {
    /** The Folder answered 304 to its stored ETag: one request, nothing written. */
    data object NotModified : FolderRefresh

    data class Refreshed(
        val upserted: Int,
        val deleted: Int,
    ) : FolderRefresh

    /** The Folder lies under an Unsynced prefix: no request, nothing stored. */
    data object Unsynced : FolderRefresh

    /** The list request failed (offline is [ApiError.Network]); nothing was written. */
    data class Failed(
        val error: ApiError,
    ) : FolderRefresh
}

/**
 * Refreshes the metadata of one Folder: `GET /notes?category=<folder>`, which the server matches exactly (no
 * sub-Folders; `""` is the Vault root), with that Folder's own ETag from `sync_state` key `list:<folder>`.
 *
 * On a 200 it upserts the Folder's Notes and deletes the SYNCED rows of that exact Folder that the server no
 * longer has, in one transaction. Rows of other Folders, sub-Folders included, are never deleted, and a row with
 * pending changes is never touched. Content is not downloaded: a changed etag clears it, and the next Sync (or
 * [fetchContent]) brings it.
 *
 * The caller holds the sync mutex (see `SyncEngine.refreshFolder`).
 */
class FolderSync(
    private val db: CittiDatabase,
    private val api: NotesApi,
    private val exclusions: ExclusionRepository,
    private val log: SyncLog = SyncLog.None,
) {
    private val notes = db.noteQueries
    private val syncState = db.syncStateQueries

    suspend fun run(folder: String): FolderRefresh {
        val unsynced = exclusions.list(ExclusionKind.UNSYNCED)
        if (folder.isNotEmpty() && categoryMatchesAny(folder, unsynced)) return FolderRefresh.Unsynced
        val hidden = exclusions.list(ExclusionKind.HIDDEN)
        val key = keyOf(folder)
        val stored = syncState.select(key).executeAsOneOrNull()?.etag

        val listed = mutableListOf<RemoteNote>()
        var etag: String? = null
        var cursor: String? = null
        // Not chunked on purpose (one Folder is small); follow the cursor anyway if the server sends one.
        do {
            val result =
                api.listNotes(
                    excludeContent = true,
                    category = folder,
                    chunkCursor = cursor,
                    ifNoneMatch = if (cursor == null) stored else null,
                )
            val page =
                when (result) {
                    is ApiResult.Failure -> {
                        log.warn("folder refresh failed ${result.error.logText()}")
                        return FolderRefresh.Failed(result.error)
                    }

                    is ApiResult.Success -> {
                        result.value
                    }
                }
            if (page !is ListResult.Page) return FolderRefresh.NotModified
            if (cursor == null) etag = page.etag
            listed += page.notes
            cursor = page.nextCursor
        } while (cursor != null)

        val serverIds = listed.mapTo(HashSet()) { it.id }
        var upserted = 0
        var deleted = 0
        db.transaction {
            for (n in listed) {
                if (n.isStub) continue
                val category = n.category ?: ""
                if (categoryMatchesAny(category, unsynced)) continue
                // 0 rows when the local row has pending changes: the upsertFromServer guard leaves it alone.
                upserted +=
                    notes
                        .upsertFromServer(
                            remoteId = n.id,
                            etag = n.etag,
                            title = n.title ?: "",
                            category = category,
                            favorite = n.favorite ?: false,
                            modified = n.modified ?: 0L,
                            readonly = n.readonly ?: false,
                            hidden = categoryMatchesAny(category, hidden),
                        ).value
                        .toInt()
            }
            for (row in notes.selectSyncedRemoteIdsInFolder(folder).executeAsList()) {
                if (row.remote_id !in serverIds) {
                    notes.deleteByLocalId(row.local_id)
                    deleted++
                }
            }
            val lastModified = listed.maxOfOrNull { it.modified ?: 0L } ?: 0L
            syncState.upsert(key, etag, lastModified, null)
        }
        log.info("folder refresh upserted=$upserted deleted=$deleted")
        return FolderRefresh.Refreshed(upserted, deleted)
    }

    /**
     * Downloads the text of one SYNCED Note whose content is missing (e.g. found by [run]), so it opens without
     * waiting for the next Sync. Returns false when nothing was written: no remote id, a request failure, or the
     * Note was edited locally in the meantime (the edit stays).
     */
    suspend fun fetchContent(localId: Long): Boolean {
        val row = notes.selectByLocalId(localId).executeAsOneOrNull() ?: return false
        val remoteId = row.remote_id ?: return false
        val remote =
            when (val result = api.getNote(remoteId)) {
                is ApiResult.Failure -> return false
                is ApiResult.Success -> result.value
            }
        val content = remote.content ?: return false
        val title = remote.title ?: row.title
        return notes.updateContent(content, excerptOf(content, title), remote.etag, localId).value > 0
    }

    companion object {
        /** The `sync_state` key of a Folder's list; the Vault root is `list:`. */
        fun keyOf(folder: String): String = "list:$folder"
    }
}
