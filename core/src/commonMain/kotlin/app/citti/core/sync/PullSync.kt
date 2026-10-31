package app.citti.core.sync

import app.citti.core.api.ApiError
import app.citti.core.api.ApiResult
import app.citti.core.api.ListResult
import app.citti.core.api.NotesApi
import app.citti.core.api.RemoteNote
import app.citti.core.api.logText
import app.citti.core.db.CittiDatabase
import app.citti.core.log.SyncLog
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.TimeSource

/** Content download progress: [done] of [total] Notes. */
data class Progress(
    val done: Int,
    val total: Int,
)

/** A Note whose content could not be downloaded; the next pull retries it. */
data class ContentFailure(
    val remoteId: Long,
    val error: ApiError,
)

sealed interface PullResult {
    /** The server list answered 304: one request, nothing else touched. */
    data object NothingChanged : PullResult

    /** [contentFetched] Notes got their content; [failures] are Notes that could not be downloaded. */
    data class Pulled(
        val contentFetched: Int,
        val failures: List<ContentFailure>,
    ) : PullResult

    /** The list request failed; the DB is consistent and the stored ETag untouched. */
    data class Failed(
        val error: ApiError,
    ) : PullResult
}

/**
 * The pull half of sync: server -> local DB.
 *
 * 1. Plain list with the stored ETag: a 304 ends the pull after that one request.
 * 2. First sync (no Notes yet): chunked pages, one DB transaction per page, then one plain list that
 *    reconciles the id set and yields the ETag (the ETag of a chunked query is not the plain one).
 *    Otherwise the 200 of the plain list is the full metadata. `pruneBefore` is not used: stubs would save
 *    bytes, but the plain 200 is already complete and keeps one code path.
 * 3. Unsynced folders are dropped client-side; Hidden folders are stored with `hidden = true`.
 * 4. Content is downloaded for the Notes that need it, with bounded parallelism.
 *
 * The ETag is stored only after a pull without any failure, so a failed content download is retried next time
 * (a stored ETag would turn the retry into a 304).
 */
class PullSync(
    private val api: NotesApi,
    private val database: CittiDatabase,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val downloadParallelism: Int = DEFAULT_PARALLELISM,
    private val writeBatchSize: Int = DEFAULT_WRITE_BATCH,
    private val log: SyncLog = SyncLog.None,
) {
    private val notes = database.noteQueries
    private val syncState = database.syncStateQueries
    private val settings = database.settingQueries

    suspend fun pull(onProgress: (Progress) -> Unit = {}): PullResult {
        val unsynced = settings.selectExclusionsByKind(UNSYNCED).executeAsList()
        val hidden = settings.selectExclusionsByKind(HIDDEN).executeAsList()
        val exclusionSignature = signature(unsynced, hidden)
        val exclusionsChanged = settings.selectSetting(SETTING_EXCLUSIONS).executeAsOneOrNull() != exclusionSignature

        val stored = syncState.select(KEY_LIST_PLAIN).executeAsOneOrNull()
        val firstSync = notes.countNotes().executeAsOne() == 0L

        log.info("pull plan firstSync=$firstSync exclusionsChanged=$exclusionsChanged storedEtag=${stored?.etag != null}")
        if (firstSync) pullChunks(unsynced, hidden)?.let { return PullResult.Failed(it) }
        // Changed exclusions need the full list again (hidden flags, newly Unsynced folders): skip the 304 check.
        val ifNoneMatch = if (firstSync || exclusionsChanged) null else stored?.etag
        val page =
            when (val result = api.listNotes(excludeContent = true, ifNoneMatch = ifNoneMatch)) {
                is ApiResult.Failure -> return PullResult.Failed(result.error)
                is ApiResult.Success -> result.value
            }
        if (page !is ListResult.Page) return PullResult.NothingChanged
        applyComplete(page.notes, unsynced, hidden)

        val outcome = downloadContent(onProgress)

        if (outcome.failures.isEmpty()) {
            val lastModified = page.notes.maxOfOrNull { it.modified ?: 0L } ?: 0L
            database.transaction {
                syncState.upsert(KEY_LIST_PLAIN, page.etag, lastModified, null)
                settings.upsertSetting(SETTING_EXCLUSIONS, exclusionSignature)
            }
        }
        return PullResult.Pulled(outcome.fetched, outcome.failures)
    }

    /** First sync: follow the cursor to the end. Returns the error of the first failed page. */
    private suspend fun pullChunks(
        unsynced: List<String>,
        hidden: List<String>,
    ): ApiError? {
        var cursor: String? = null
        var pageNumber = 0
        do {
            when (val result = api.listNotes(excludeContent = true, chunkSize = chunkSize, chunkCursor = cursor)) {
                is ApiResult.Failure -> {
                    return result.error
                }

                is ApiResult.Success -> {
                    val page = result.value as ListResult.Page
                    pageNumber++
                    log.info("pull chunk page=$pageNumber notes=${page.notes.size} pending=${page.pending ?: "?"}")
                    // A cursor request repeats the already-seen Notes as stubs; those carry nothing new.
                    database.transaction { upsert(page.notes.filterNot { it.isStub }, unsynced, hidden) }
                    cursor = page.nextCursor
                }
            }
        } while (cursor != null)
        return null
    }

    /**
     * [list] is the complete metadata of the server: upsert it, drop Notes that left the server and
     * Notes under an Unsynced folder.
     *
     * Only SYNCED Notes are ever deleted. A DIRTY Note deleted on the server is left alone (its pending edit
     * must not be lost); the push side decides what to do with it. NEW Notes have no remote id.
     */
    private fun applyComplete(
        list: List<RemoteNote>,
        unsynced: List<String>,
        hidden: List<String>,
    ) {
        val serverIds = list.mapTo(HashSet(list.size * 2)) { it.id }
        database.transaction {
            upsert(list.filterNot { it.isStub }, unsynced, hidden)
            for (row in notes.selectSyncIndex().executeAsList()) {
                if (row.sync_state != SYNCED) continue
                if (row.remote_id !in serverIds || categoryMatchesAny(row.category, unsynced)) {
                    notes.deleteByLocalId(row.local_id)
                }
            }
        }
    }

    private fun upsert(
        items: List<RemoteNote>,
        unsynced: List<String>,
        hidden: List<String>,
    ) {
        for (n in items) {
            val category = n.category ?: ""
            if (categoryMatchesAny(category, unsynced)) continue
            notes.upsertFromServer(
                remoteId = n.id,
                etag = n.etag,
                title = n.title ?: "",
                category = category,
                favorite = n.favorite ?: false,
                modified = n.modified ?: 0L,
                readonly = n.readonly ?: false,
                hidden = categoryMatchesAny(category, hidden),
            )
        }
    }

    private class Downloaded(
        val fetched: Int,
        val failures: List<ContentFailure>,
    )

    private suspend fun downloadContent(onProgress: (Progress) -> Unit): Downloaded {
        val targets = notes.selectNeedingContent().executeAsList()
        val total = targets.size
        if (total == 0) return Downloaded(0, emptyList())
        log.info("pull content start total=$total")
        val started = TimeSource.Monotonic.markNow()

        val failures = mutableListOf<ContentFailure>()
        var fetched = 0
        val batch = ArrayList<Pair<Long, RemoteNote>>(writeBatchSize)

        fun flush() {
            if (batch.isEmpty()) return
            database.transaction { batch.forEach { (localId, n) -> notes.updateContent(n.content ?: "", n.etag, localId) } }
            fetched += batch.size
            batch.clear()
        }

        coroutineScope {
            val permits = Semaphore(downloadParallelism)
            val results = Channel<Pair<Long, ApiResult<RemoteNote>>>(Channel.UNLIMITED)
            val remoteIds = targets.associate { it.local_id to it.remote_id }
            for ((localId, remoteId) in remoteIds) {
                launch { results.send(localId to permits.withPermit { api.getNote(remoteId) }) }
            }
            repeat(total) { index ->
                val (localId, result) = results.receive()
                when (result) {
                    is ApiResult.Success -> {
                        batch += localId to result.value
                        if (batch.size >= writeBatchSize) flush()
                    }

                    is ApiResult.Failure -> {
                        failures += ContentFailure(remoteIds.getValue(localId), result.error)
                        log.warn("pull content failed remote=${remoteIds.getValue(localId)} ${result.error.logText()}")
                    }
                }
                onProgress(Progress(index + 1, total))
                val done = index + 1
                if (done % PROGRESS_LOG_EVERY == 0 && done < total) {
                    logProgress(done, total, started.elapsedNow().inWholeMilliseconds)
                }
            }
            flush()
        }
        logProgress(total, total, started.elapsedNow().inWholeMilliseconds)
        return Downloaded(fetched, failures)
    }

    private fun logProgress(
        done: Int,
        total: Int,
        elapsedMs: Long,
    ) {
        val perSecond = if (elapsedMs > 0) done * 1000.0 / elapsedMs else 0.0
        val rate = (perSecond * 10).toLong() / 10.0
        log.info("pull content $done/$total elapsed=${elapsedMs}ms rate=$rate notes/s")
    }

    private fun signature(
        unsynced: List<String>,
        hidden: List<String>,
    ) = "U:" + unsynced.sorted().joinToString("\n") + "\nH:" + hidden.sorted().joinToString("\n")

    companion object {
        const val KEY_LIST_PLAIN = "list-plain"
        const val DEFAULT_CHUNK_SIZE = 500
        const val DEFAULT_PARALLELISM = 8
        const val DEFAULT_WRITE_BATCH = 50
        private const val PROGRESS_LOG_EVERY = 250
        private const val SETTING_EXCLUSIONS = "sync.exclusions-applied"
        private const val UNSYNCED = "UNSYNCED"
        private const val HIDDEN = "HIDDEN"
        private const val SYNCED = "SYNCED"
    }
}
