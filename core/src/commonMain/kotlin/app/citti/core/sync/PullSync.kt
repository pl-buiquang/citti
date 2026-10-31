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
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
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
 * A content download that fails with a network or 5xx error is retried a few times with a short backoff. After
 * [circuitBreakerThreshold] Notes in a row that still fail on the network (the phone lost its connection, e.g.
 * the screen went off) the content phase stops: everything already written stays, and the pull returns
 * `Failed(Network)` instead of counting hundreds of failures. An auth error stops it at once.
 *
 * The ETag is stored only after a pull without any failure, so a failed or skipped content download is retried
 * next time (a stored ETag would turn the retry into a 304), and only the Notes still missing content are fetched.
 */
class PullSync(
    private val api: NotesApi,
    private val database: CittiDatabase,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val downloadParallelism: Int = DEFAULT_PARALLELISM,
    private val writeBatchSize: Int = DEFAULT_WRITE_BATCH,
    private val log: SyncLog = SyncLog.None,
    /** Pause before each retry of a content download; its size is the number of retries per Note. */
    private val retryBackoffMillis: List<Long> = DEFAULT_RETRY_BACKOFF,
    /** Consecutive Notes that still fail with a network error after retries before the content phase stops. */
    private val circuitBreakerThreshold: Int = DEFAULT_CIRCUIT_BREAKER,
    /** Injected so tests run in virtual time. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
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
        outcome.abortedBy?.let { return PullResult.Failed(it) }

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
        /** Set when the content phase was stopped early (auth error or network circuit breaker). */
        val abortedBy: ApiError? = null,
    )

    private suspend fun fetchWithRetry(remoteId: Long): ApiResult<RemoteNote> {
        var attempt = 0
        while (true) {
            val result = api.getNote(remoteId)
            if (result !is ApiResult.Failure || !result.error.isRetryable() || attempt >= retryBackoffMillis.size) return result
            val wait = retryBackoffMillis[attempt]
            attempt++
            log.warn(
                "pull content retry remote=$remoteId attempt=$attempt/${retryBackoffMillis.size} wait=${wait}ms ${result.error.logText()}",
            )
            sleep(wait)
        }
    }

    private fun ApiError.isRetryable() = this is ApiError.Network || this is ApiError.Server

    private suspend fun downloadContent(onProgress: (Progress) -> Unit): Downloaded {
        val targets = notes.selectNeedingContent().executeAsList()
        val total = targets.size
        if (total == 0) return Downloaded(0, emptyList())
        log.info("pull content start total=$total")
        val started = TimeSource.Monotonic.markNow()

        val failures = mutableListOf<ContentFailure>()
        var fetched = 0
        val batch = ArrayList<Pair<Long, RemoteNote>>(writeBatchSize)
        val titles = targets.associate { it.local_id to it.title }

        fun flush() {
            if (batch.isEmpty()) return
            database.transaction {
                batch.forEach { (localId, n) ->
                    val content = n.content ?: ""
                    val title = n.title ?: titles.getValue(localId)
                    notes.updateContent(content, excerptOf(content, title), n.etag, localId)
                }
            }
            fetched += batch.size
            batch.clear()
        }

        var abortedBy: ApiError? = null
        var networkStreak = 0
        var attempted = 0
        coroutineScope {
            val remoteIds = targets.associate { it.local_id to it.remote_id }
            val queue = Channel<Long>(Channel.UNLIMITED)
            remoteIds.keys.forEach { queue.trySend(it) }
            queue.close()
            // A fixed pool of workers, and a rendezvous channel: a worker cannot start its next download before
            // the previous result was handled, so the circuit breaker below stops the downloads within about
            // `downloadParallelism` requests instead of lagging behind a crowd of already-started ones.
            val results = Channel<Pair<Long, ApiResult<RemoteNote>>>(Channel.RENDEZVOUS)
            val workers =
                List(downloadParallelism) {
                    launch {
                        for (localId in queue) {
                            results.send(localId to fetchWithRetry(remoteIds.getValue(localId)))
                        }
                    }
                }
            launch {
                workers.joinAll()
                results.close()
            }
            var index = 0
            for ((localId, result) in results) {
                attempted = ++index
                when (result) {
                    is ApiResult.Success -> {
                        networkStreak = 0
                        batch += localId to result.value
                        if (batch.size >= writeBatchSize) flush()
                    }

                    is ApiResult.Failure -> {
                        failures += ContentFailure(remoteIds.getValue(localId), result.error)
                        log.warn("pull content failed remote=${remoteIds.getValue(localId)} ${result.error.logText()}")
                        networkStreak = if (result.error is ApiError.Network) networkStreak + 1 else 0
                        if (result.error is ApiError.Auth) {
                            abortedBy = result.error
                        } else if (networkStreak >= circuitBreakerThreshold) {
                            abortedBy = result.error
                        }
                    }
                }
                if (abortedBy != null) {
                    val skipped = total - attempted
                    log.error(
                        "pull content stopped after $attempted/$total ${(abortedBy as ApiError).logText()} " +
                            "networkStreak=$networkStreak skipped=$skipped fetched=${fetched + batch.size}",
                    )
                    coroutineContext.cancelChildren()
                    break
                }
                onProgress(Progress(index, total))
                if (index % PROGRESS_LOG_EVERY == 0 && index < total) {
                    logProgress(index, total, started.elapsedNow().inWholeMilliseconds)
                }
            }
            flush()
        }
        if (abortedBy == null) logProgress(total, total, started.elapsedNow().inWholeMilliseconds)
        // Failures that led to a network stop are not reported: the run fails as a whole and the next one resumes.
        return Downloaded(fetched, if (abortedBy == null) failures else emptyList(), abortedBy)
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
        const val DEFAULT_CIRCUIT_BREAKER = 5
        val DEFAULT_RETRY_BACKOFF = listOf(500L, 1500L)
        private const val PROGRESS_LOG_EVERY = 250
        private const val SETTING_EXCLUSIONS = "sync.exclusions-applied"
        private const val UNSYNCED = "UNSYNCED"
        private const val HIDDEN = "HIDDEN"
        private const val SYNCED = "SYNCED"
    }
}
