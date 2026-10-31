package app.citti.core.sync

import app.citti.core.api.ApiError
import app.citti.core.db.CittiDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

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

    /** The run reached the end. [conflicts] counts the Conflict copies created by the push. */
    data class Synced(
        val pushed: Int,
        val pulled: Int,
        val conflicts: Int,
        val failures: List<NoteFailure>,
    ) : SyncOutcome

    /** A run-stopping [error]; counts are what got done before it. */
    data class Failed(
        val error: ApiError,
        val pushed: Int = 0,
        val conflicts: Int = 0,
        val failures: List<NoteFailure> = emptyList(),
    ) : SyncOutcome
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
    private val now: () -> Long,
) {
    private val settings = db.settingQueries
    private val notes = db.noteQueries
    private val pending = PendingChanges(db)
    private val mutex = Mutex()

    private var lastSuccessAt: Long? = settings.selectSetting(KEY_LAST_AT).executeAsOneOrNull()?.toLongOrNull()
    private var lastResult: SyncResultKind? =
        settings.selectSetting(KEY_LAST_RESULT).executeAsOneOrNull()?.let { name ->
            SyncResultKind.entries.firstOrNull { it.name == name }
        }

    private val _state = MutableStateFlow<SyncStatus>(idle())
    val state: StateFlow<SyncStatus> = _state.asStateFlow()

    /** Recounts pending changes and Conflict copies, e.g. after a local edit. No-op while a run is in progress. */
    fun refreshCounts() {
        val current = _state.value
        if (current is SyncStatus.Running) return
        _state.value = withCounts(current)
    }

    /** [trigger] is accepted for callers' policy and logging; every trigger runs the same push then pull. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun sync(trigger: SyncTrigger): SyncOutcome {
        if (!mutex.tryLock()) return SyncOutcome.AlreadyRunning
        try {
            return run()
        } catch (e: CancellationException) {
            _state.value = idle()
            throw e
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun run(): SyncOutcome {
        running(SyncPhase.Pushing, null)
        val pushed =
            when (val result = push.push()) {
                is PushResult.Failed -> return fail(result.error)
                is PushResult.Done -> result
            }
        val pushedCount = pushed.updated + pushed.created + pushed.deleted
        val pushFailures = pushed.failures.map { NoteFailure(it.localId, null, it.error) }

        running(SyncPhase.Pulling, null)
        val outcome =
            when (val result = pull.pull { running(SyncPhase.Pulling, it) }) {
                is PullResult.Failed -> {
                    return fail(result.error, pushedCount, pushed.conflicts.size, pushFailures)
                }

                PullResult.NothingChanged -> {
                    if (pushedCount == 0 && pushed.conflicts.isEmpty() && pushFailures.isEmpty()) {
                        SyncOutcome.NothingChanged
                    } else {
                        SyncOutcome.Synced(pushedCount, 0, pushed.conflicts.size, pushFailures)
                    }
                }

                is PullResult.Pulled -> {
                    val failures = pushFailures + result.failures.map { NoteFailure(null, it.remoteId, it.error) }
                    SyncOutcome.Synced(pushedCount, result.contentFetched, pushed.conflicts.size, failures)
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
        return SyncOutcome.Failed(error, pushed, conflicts, failures)
    }

    private fun describe(error: ApiError): String =
        when (error) {
            is ApiError.Http -> "Unexpected response (HTTP ${error.status})"
            is ApiError.BadResponse -> error.message ?: "Unreadable response"
            ApiError.NotFound -> "Not found"
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
    }
}
