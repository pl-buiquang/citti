package app.citti.core.sync

import app.citti.core.api.ApiError
import app.citti.core.api.RequestStats
import app.citti.core.api.logText
import app.citti.core.dav.DavSetup
import app.citti.core.db.CittiDatabase
import app.citti.core.log.SyncLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlin.time.TimeSource

/** Why a sync run starts. The engine treats all alike; callers use it for policy (e.g. no retry after auth failure). */
enum class SyncTrigger { AppOpen, Manual, Periodic, AfterEdit }

/** What the last finished run ended with, persisted so "Synced 2 min ago" survives restarts. */
enum class SyncResultKind { Synced, NothingChanged, SyncedWithFailures, Offline, AuthFailed, ServerError, Error }

enum class SyncPhase { Pushing, Pulling }

/** A Note that could not be pushed or downloaded; the next run retries it. */
data class NoteFailure(
    val localId: Long?,
    val remoteId: Long?,
    val error: ApiError,
)

sealed interface SyncOutcome {
    /** Another run is in progress; this call did nothing. */
    data object AlreadyRunning : SyncOutcome

    /** Nothing pushed, and the server list answered 304. */
    data object NothingChanged : SyncOutcome

    /**
     * The run reached the end. [conflicts] counts the Conflict copies created by the push, [dropped] the untouched
     * New notes it deleted because the server had them, [deleteUndone] the local deletes the server undid.
     */
    data class Synced(
        val pushed: Int,
        val pulled: Int,
        val conflicts: Int,
        val failures: List<NoteFailure>,
        val dropped: List<Dropped> = emptyList(),
        val deleteUndone: Int = 0,
    ) : SyncOutcome

    /** A run-stopping [error]; counts are what got done before it. */
    data class Failed(
        val error: ApiError,
        val pushed: Int = 0,
        val conflicts: Int = 0,
        val failures: List<NoteFailure> = emptyList(),
        val dropped: List<Dropped> = emptyList(),
        val deleteUndone: Int = 0,
    ) : SyncOutcome
}

/** One-off things a run did that an open screen reacts to. */
sealed interface SyncEvent {
    /** The untouched New note [localId] was deleted: the server already had it as [serverLocalId]. */
    data class Dropped(
        val localId: Long,
        val serverLocalId: Long,
    ) : SyncEvent
}

/** What the UI shows: drawer line, badges, banners. Every variant carries the pending and Conflict copy counts. */
sealed interface SyncStatus {
    val pendingChanges: Int
    val conflictCopies: Int
    val lastSuccessAt: Long?

    data class Idle(
        override val lastSuccessAt: Long?,
        val lastResult: SyncResultKind?,
        override val pendingChanges: Int = 0,
        override val conflictCopies: Int = 0,
    ) : SyncStatus

    data class Running(
        val phase: SyncPhase,
        val progress: Progress?,
        override val lastSuccessAt: Long?,
        override val pendingChanges: Int = 0,
        override val conflictCopies: Int = 0,
    ) : SyncStatus

    data class Offline(
        override val lastSuccessAt: Long?,
        override val pendingChanges: Int = 0,
        override val conflictCopies: Int = 0,
    ) : SyncStatus

    /** 401 or 403: do not retry automatically, the user must sign in again. */
    data class AuthFailed(
        val status: Int,
        override val lastSuccessAt: Long?,
        override val pendingChanges: Int = 0,
        override val conflictCopies: Int = 0,
    ) : SyncStatus

    data class ServerError(
        val status: Int,
        override val lastSuccessAt: Long?,
        override val pendingChanges: Int = 0,
        override val conflictCopies: Int = 0,
    ) : SyncStatus

    data class Failed(
        val message: String,
        override val lastSuccessAt: Long?,
        override val pendingChanges: Int = 0,
        override val conflictCopies: Int = 0,
    ) : SyncStatus
}

/**
 * Runs one sync at a time: push first, then pull. [now] returns epoch seconds.
 *
 * A fatal push error (auth, network, server) skips the pull. Per-Note push failures do not: the pull still runs
 * and the failures are part of the outcome. Cancellation propagates and leaves the state Idle.
 */
class SyncEngine(
    private val push: PushSync,
    private val pull: PullSync,
    db: CittiDatabase,
    private val log: SyncLog = SyncLog.None,
    private val stats: RequestStats? = null,
    /** Pause before the one start-up retry (see [sync]); injected so tests run in virtual time. */
    private val startupRetryDelayMillis: Long = DEFAULT_STARTUP_RETRY_DELAY,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /** Stores the WebDAV user id and Notes folder; refreshed after an app-open sync, or when nothing is stored yet. */
    private val dav: DavSetup? = null,
    /** Needed by [refreshFolder] and [fetchContent] only. */
    private val folderSync: FolderSync? = null,
    /** Called after [refreshFolder] stored or deleted Notes, outside the mutex (the widgets redraw from it). */
    private val onFolderChanged: () -> Unit = {},
    private val now: () -> Long,
) {
    private val settings = db.settingQueries
    private val notes = db.noteQueries
    private val pending = PendingChanges(db)
    private val mutex = Mutex()

    /** Set when [push] found the engine busy: the holder runs one more push before it releases the mutex. */
    private var pushRequested = false

    private var lastSuccessAt: Long? = settings.selectSetting(KEY_LAST_AT).executeAsOneOrNull()?.toLongOrNull()
    private var lastResult: SyncResultKind? =
        settings.selectSetting(KEY_LAST_RESULT).executeAsOneOrNull()?.let { name ->
            SyncResultKind.entries.firstOrNull { it.name == name }
        }

    private val _state = MutableStateFlow<SyncStatus>(idle())
    val state: StateFlow<SyncStatus> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SyncEvent>(replay = 0, extraBufferCapacity = EVENT_BUFFER)

    /** Emitted once the push has written the change; no replay. */
    val events: SharedFlow<SyncEvent> = _events.asSharedFlow()

    /** Recounts pending changes and Conflict copies, e.g. after a local edit. No-op while a run is in progress. */
    fun refreshCounts() {
        val current = _state.value
        if (current is SyncStatus.Running) return
        _state.value = withCounts(current)
    }

    /** Set once the content phase reports progress, i.e. the first requests of the run went through. */
    private var contentStarted = false

    /**
     * Every trigger runs the same push then pull. [trigger] is mostly left to callers' policy, with one exception:
     * an app-open or manual sync whose first request fails with a network error is retried once after a short
     * pause, because right after a cold start or a wake-up the network is often not usable for a moment.
     */
    suspend fun sync(trigger: SyncTrigger): SyncOutcome {
        if (!mutex.tryLock()) {
            log.info("sync skipped trigger=$trigger: already running")
            return SyncOutcome.AlreadyRunning
        }
        val started = TimeSource.Monotonic.markNow()
        val before = stats?.snapshot()
        log.info("sync start trigger=$trigger")
        try {
            var outcome = run()
            if (shouldRetryStart(trigger, outcome)) {
                log.warn("sync retry trigger=$trigger after ${startupRetryDelayMillis}ms: first request failed on the network")
                sleep(startupRetryDelayMillis)
                outcome = run()
            }
            val after = stats?.snapshot()
            val traffic =
                if (before != null && after != null) {
                    " requests=${after.requests - before.requests} bytes=${after.bytes - before.bytes}"
                } else {
                    ""
                }
            val ms = started.elapsedNow().inWholeMilliseconds
            log.info("sync done trigger=$trigger ${summary(outcome)} duration=${ms}ms$traffic")
            refreshDav(trigger, outcome)
            pushRequestedMeanwhile()
            return outcome
        } catch (e: CancellationException) {
            log.warn("sync cancelled trigger=$trigger after ${started.elapsedNow().inWholeMilliseconds}ms")
            _state.value = idle()
            throw e
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Pushes the queue and nothing else: no full list request (it costs a round trip even when the answer is a 304).
     * New notes still cost one folder list per folder (push-time check, ADR 0006). Meant for [SyncTrigger.AfterEdit].
     * When a run is in progress it only records the request and the run pushes again before it ends, so edits made
     * during a long sync do not wait for the next periodic one.
     */
    suspend fun push(trigger: SyncTrigger = SyncTrigger.AfterEdit): SyncOutcome {
        if (!mutex.tryLock()) {
            pushRequested = true
            log.info("push skipped trigger=$trigger: already running, will push when it ends")
            return SyncOutcome.AlreadyRunning
        }
        try {
            val outcome = pushOnly(trigger)
            pushRequestedMeanwhile()
            return outcome
        } catch (e: CancellationException) {
            log.warn("push cancelled trigger=$trigger")
            _state.value = idle()
            throw e
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Refreshes one Folder's metadata ([FolderSync]). Waits for a running sync or push instead of returning
     * [SyncOutcome.AlreadyRunning], because the caller (creation, Rename, Move) needs the answer. It is a check, not
     * a Sync: [state] never shows Running, only the counts are updated.
     */
    suspend fun refreshFolder(folder: String): FolderRefresh {
        val sync = checkNotNull(folderSync) { "SyncEngine built without a FolderSync" }
        mutex.lock()
        val result =
            try {
                val started = TimeSource.Monotonic.markNow()
                sync.run(folder).also {
                    log.info("folder refresh done result=${it::class.simpleName} duration=${started.elapsedNow().inWholeMilliseconds}ms")
                    refreshCounts()
                }
            } finally {
                mutex.unlock()
            }
        if (result is FolderRefresh.Refreshed && result.upserted + result.deleted > 0) onFolderChanged()
        // A push after an edit that found the mutex taken by this refresh would otherwise wait for the next Sync.
        if (pushRequested) {
            pushRequested = false
            push(SyncTrigger.AfterEdit)
        }
        return result
    }

    /**
     * Downloads one SYNCED Note's missing text (e.g. after [refreshFolder]) so it opens without waiting for the
     * next Sync. Outside the mutex: the write is guarded like the pull's, and never overwrites a local edit.
     */
    suspend fun fetchContent(localId: Long): Boolean =
        checkNotNull(folderSync) { "SyncEngine built without a FolderSync" }.fetchContent(localId)

    /** Runs the one more push that a busy [push] asked for, if anything is still pending. Holds the mutex. */
    private suspend fun pushRequestedMeanwhile() {
        while (pushRequested) {
            pushRequested = false
            if (pending.count() == 0) return
            val outcome = pushOnly(SyncTrigger.AfterEdit)
            // Offline or failed: leave it to the periodic job and the next app open.
            if (outcome is SyncOutcome.Failed) return
        }
    }

    private suspend fun pushOnly(trigger: SyncTrigger): SyncOutcome {
        val started = TimeSource.Monotonic.markNow()
        val before = stats?.snapshot()
        log.info("push start trigger=$trigger pending=${pending.count()}")
        running(SyncPhase.Pushing, null)
        val outcome =
            when (val result = pushAndEmit()) {
                is PushResult.Failed -> {
                    log.error("push failed ${result.error.logText()}")
                    fail(result.error, dropped = result.dropped)
                }

                is PushResult.Done -> {
                    val count = result.updated + result.created + result.deleted
                    val failures = result.failures.map { NoteFailure(it.localId, null, it.error) }
                    // lastSuccessAt stays: only a full sync, with its pull, says the whole Vault is up to date.
                    _state.value = idle()
                    if (count == 0 && result.conflicts.isEmpty() && failures.isEmpty() && result.dropped.isEmpty() &&
                        result.deleteUndone == 0
                    ) {
                        SyncOutcome.NothingChanged
                    } else {
                        SyncOutcome.Synced(count, 0, result.conflicts.size, failures, result.dropped, result.deleteUndone)
                    }
                }
            }
        val after = stats?.snapshot()
        val traffic = if (before != null && after != null) " requests=${after.requests - before.requests}" else ""
        log.info("push done trigger=$trigger ${summary(outcome)} duration=${started.elapsedNow().inWholeMilliseconds}ms$traffic")
        return outcome
    }

    /** Runs the push and emits a [SyncEvent.Dropped] per dropped New note, after the push wrote it. */
    private suspend fun pushAndEmit(): PushResult =
        push.push().also { result ->
            val dropped =
                when (result) {
                    is PushResult.Done -> result.dropped
                    is PushResult.Failed -> result.dropped
                }
            dropped.forEach { _events.tryEmit(SyncEvent.Dropped(it.localId, it.serverLocalId)) }
        }

    /** Outside the request count of the sync: a no-change sync stays one request. A failure is ignored. */
    private suspend fun refreshDav(
        trigger: SyncTrigger,
        outcome: SyncOutcome,
    ) {
        val setup = dav ?: return
        if (outcome is SyncOutcome.Failed || outcome == SyncOutcome.AlreadyRunning) return
        if (trigger == SyncTrigger.AppOpen || !setup.isStored) setup.refresh()
    }

    private fun shouldRetryStart(
        trigger: SyncTrigger,
        outcome: SyncOutcome,
    ): Boolean =
        (trigger == SyncTrigger.AppOpen || trigger == SyncTrigger.Manual) &&
            outcome is SyncOutcome.Failed &&
            outcome.error is ApiError.Network &&
            outcome.pushed == 0 &&
            !contentStarted

    private fun summary(outcome: SyncOutcome): String =
        when (outcome) {
            SyncOutcome.AlreadyRunning -> {
                "outcome=AlreadyRunning"
            }

            SyncOutcome.NothingChanged -> {
                "outcome=NothingChanged pushed=0 pulled=0 conflicts=0 failures=0"
            }

            is SyncOutcome.Synced -> {
                val kind = if (outcome.failures.isEmpty()) "Synced" else "SyncedWithFailures"
                "outcome=$kind pushed=${outcome.pushed} pulled=${outcome.pulled} " +
                    "conflicts=${outcome.conflicts} failures=${outcome.failures.size}"
            }

            is SyncOutcome.Failed -> {
                "outcome=Failed error=${outcome.error.logText()} pushed=${outcome.pushed} pulled=0 " +
                    "conflicts=${outcome.conflicts} failures=${outcome.failures.size}"
            }
        }

    private suspend fun run(): SyncOutcome {
        contentStarted = false
        running(SyncPhase.Pushing, null)
        log.info("phase push start pending=${pending.count()}")
        val pushed =
            when (val result = pushAndEmit()) {
                is PushResult.Failed -> {
                    log.error("phase push failed ${result.error.logText()}")
                    return fail(result.error, dropped = result.dropped)
                }

                is PushResult.Done -> {
                    result
                }
            }
        log.info(
            "phase push end updated=${pushed.updated} created=${pushed.created} deleted=${pushed.deleted} " +
                "conflicts=${pushed.conflicts.size} skippedReadOnly=${pushed.skippedReadOnly} failures=${pushed.failures.size} " +
                "dropped=${pushed.dropped.size} deferredNew=${pushed.deferredNew}",
        )
        val pushedCount = pushed.updated + pushed.created + pushed.deleted
        val pushFailures = pushed.failures.map { NoteFailure(it.localId, null, it.error) }

        running(SyncPhase.Pulling, null)
        log.info("phase pull start")
        val outcome =
            when (
                val result =
                    pull.pull {
                        contentStarted = true
                        running(SyncPhase.Pulling, it)
                    }
            ) {
                is PullResult.Failed -> {
                    log.error("phase pull failed ${result.error.logText()}")
                    return fail(result.error, pushedCount, pushed.conflicts.size, pushFailures, pushed.dropped, pushed.deleteUndone)
                }

                PullResult.NothingChanged -> {
                    log.info("phase pull end result=NothingChanged")
                    if (pushedCount == 0 && pushed.conflicts.isEmpty() && pushFailures.isEmpty() && pushed.dropped.isEmpty() &&
                        pushed.deleteUndone == 0
                    ) {
                        SyncOutcome.NothingChanged
                    } else {
                        SyncOutcome.Synced(pushedCount, 0, pushed.conflicts.size, pushFailures, pushed.dropped, pushed.deleteUndone)
                    }
                }

                is PullResult.Pulled -> {
                    log.info("phase pull end result=Pulled contentFetched=${result.contentFetched} failures=${result.failures.size}")
                    val failures = pushFailures + result.failures.map { NoteFailure(null, it.remoteId, it.error) }
                    SyncOutcome.Synced(
                        pushedCount,
                        result.contentFetched,
                        pushed.conflicts.size,
                        failures,
                        pushed.dropped,
                        pushed.deleteUndone,
                    )
                }
            }
        lastResult =
            when {
                outcome is SyncOutcome.NothingChanged -> SyncResultKind.NothingChanged
                outcome is SyncOutcome.Synced && outcome.failures.isNotEmpty() -> SyncResultKind.SyncedWithFailures
                else -> SyncResultKind.Synced
            }
        lastSuccessAt = now()
        persist()
        _state.value = idle()
        return outcome
    }

    private fun fail(
        error: ApiError,
        pushed: Int = 0,
        conflicts: Int = 0,
        failures: List<NoteFailure> = emptyList(),
        dropped: List<Dropped> = emptyList(),
        deleteUndone: Int = 0,
    ): SyncOutcome {
        val (p, c) = counts()
        val at = lastSuccessAt
        val (status, kind) =
            when (error) {
                is ApiError.Network -> SyncStatus.Offline(at, p, c) to SyncResultKind.Offline
                is ApiError.Auth -> SyncStatus.AuthFailed(error.status, at, p, c) to SyncResultKind.AuthFailed
                is ApiError.Server -> SyncStatus.ServerError(error.status, at, p, c) to SyncResultKind.ServerError
                else -> SyncStatus.Failed(describe(error), at, p, c) to SyncResultKind.Error
            }
        lastResult = kind
        persist()
        _state.value = status
        return SyncOutcome.Failed(error, pushed, conflicts, failures, dropped, deleteUndone)
    }

    private fun describe(error: ApiError): String =
        when (error) {
            is ApiError.Http -> "Unexpected response (HTTP ${error.status})"
            is ApiError.BadResponse -> error.message ?: "Unreadable response"
            ApiError.NotFound -> "Not found"
            ApiError.Unreadable -> "Note locked on the server"
            else -> error.toString()
        }

    private fun running(
        phase: SyncPhase,
        progress: Progress?,
    ) {
        val (p, c) = counts()
        _state.value = SyncStatus.Running(phase, progress, lastSuccessAt, p, c)
    }

    private fun persist() {
        lastSuccessAt?.let { settings.upsertSetting(KEY_LAST_AT, it.toString()) }
        lastResult?.let { settings.upsertSetting(KEY_LAST_RESULT, it.name) }
    }

    private fun counts(): Pair<Int, Int> = pending.count() to notes.countConflictCopies().executeAsOne().toInt()

    private fun idle(): SyncStatus.Idle {
        val (p, c) = counts()
        return SyncStatus.Idle(lastSuccessAt, lastResult, p, c)
    }

    private fun withCounts(status: SyncStatus): SyncStatus {
        val (p, c) = counts()
        return when (status) {
            is SyncStatus.Idle -> status.copy(pendingChanges = p, conflictCopies = c)
            is SyncStatus.Running -> status.copy(pendingChanges = p, conflictCopies = c)
            is SyncStatus.Offline -> status.copy(pendingChanges = p, conflictCopies = c)
            is SyncStatus.AuthFailed -> status.copy(pendingChanges = p, conflictCopies = c)
            is SyncStatus.ServerError -> status.copy(pendingChanges = p, conflictCopies = c)
            is SyncStatus.Failed -> status.copy(pendingChanges = p, conflictCopies = c)
        }
    }

    companion object {
        const val KEY_LAST_AT = "sync.last-at"
        const val KEY_LAST_RESULT = "sync.last-result"
        const val DEFAULT_STARTUP_RETRY_DELAY = 2_000L
        private const val EVENT_BUFFER = 16
    }
}
