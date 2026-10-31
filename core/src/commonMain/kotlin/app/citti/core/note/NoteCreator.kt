package app.citti.core.note

import app.citti.core.db.CittiDatabase
import app.citti.core.settings.ExclusionRepository
import app.citti.core.sync.FolderRefresh
import app.citti.core.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How long the creation check waits for the Folder refresh before the Note is created unchecked (ADR 0006). */
const val CREATION_CHECK_MS = 3_000L

/**
 * The only way the UI creates a Note (ADR 0006): [check] looks the `(folder, title)` up locally, then refreshes
 * the Folder from the server for at most [CREATION_CHECK_MS] and looks again; [create] inserts the NEW row and
 * schedules the push. Titles match exactly, as on the server: `t` next to `T` is not a clash.
 *
 * The refresh runs in [appScope], so it outlives a check that timed out or a caller that left, and its ETag still
 * lands. [clock] returns epoch seconds (the app passes `AppConfig`'s clock).
 */
class NoteCreator(
    private val db: CittiDatabase,
    private val engine: SyncEngine,
    private val appScope: CoroutineScope,
    private val exclusions: ExclusionRepository,
    /** `AfterEditPush.schedule()`. */
    private val afterEdit: () -> Unit,
    private val clock: () -> Long,
) {
    sealed interface Check {
        /** A Note with this title is in the Folder. */
        data class Exists(
            val localId: Long,
        ) : Check

        /** The server answered and has no such Note. */
        data object Free : Check

        /** Offline, a failed refresh or the cap was reached: only the local DB was checked. */
        data object Unchecked : Check
    }

    private val notes = db.noteQueries

    /**
     * A local hit answers [Check.Exists] at once and still refreshes the Folder in the background. On a miss, waits
     * for the Folder refresh up to [timeoutMs] (coroutine time), then looks again.
     */
    suspend fun check(
        folder: String,
        title: String,
        timeoutMs: Long = CREATION_CHECK_MS,
    ): Check {
        lookup(folder, title)?.let { local ->
            appScope.launch { engine.refreshFolder(folder) }
            return Check.Exists(local)
        }
        val refresh = appScope.async { engine.refreshFolder(folder) }
        val result = withTimeoutOrNull(timeoutMs) { refresh.await() }
        lookup(folder, title)?.let { return Check.Exists(it) }
        return when (result) {
            FolderRefresh.NotModified, is FolderRefresh.Refreshed -> Check.Free
            null, FolderRefresh.Unsynced, is FolderRefresh.Failed -> Check.Unchecked
        }
    }

    /** [title] when the Folder has no Note with it, else the first free `Title (2)`, `Title (3)`… (exact match). */
    fun freeTitle(
        folder: String,
        title: String,
    ): String {
        if (!taken(folder, title)) return title
        var n = 2
        while (taken(folder, "$title ($n)")) n++
        return "$title ($n)"
    }

    /**
     * Inserts a NEW Note holding [content] (also its `created_from`, so an untouched duplicate is dropped at push)
     * and schedules the push. [folder] must be normalised and outside the Unsynced folders, and [title] valid
     * ([NameRules]); the caller validated both. Returns the local id.
     */
    suspend fun create(
        folder: String,
        title: String,
        content: String,
    ): Long {
        require(NameRules.validateTitle(title) == null) { "invalid title" }
        require(NameRules.normaliseFolder(folder, exclusions).getOrNull() == folder) { "invalid folder" }
        val localId =
            db.transactionWithResult {
                notes.insertNew(title, folder, content, excerptOf(content, title), clock(), null, content)
                notes.lastInsertedLocalId().executeAsOne()
            }
        engine.refreshCounts()
        afterEdit()
        return localId
    }

    private fun lookup(
        folder: String,
        title: String,
    ): Long? = notes.selectExactByFolderAndTitle(folder, title).executeAsOneOrNull()

    private fun taken(
        folder: String,
        title: String,
    ) = notes.folderHasTitle(folder, title).executeAsOne() > 0
}
