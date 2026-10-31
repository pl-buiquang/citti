package app.citti.core.settings

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.citti.core.db.CittiDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

enum class ExclusionKind { UNSYNCED, HIDDEN }

/** Trims and strips leading, trailing and doubled slashes; null when nothing is left. */
fun normalizeFolderPath(raw: String): String? =
    raw
        .trim()
        .split('/')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("/")
        .ifEmpty { null }

/** The Unsynced and Hidden folder lists (`folder_exclusion` table) and the Folders known locally. */
class ExclusionRepository(
    private val db: CittiDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val settings = db.settingQueries
    private val notes = db.noteQueries
    private val syncState = db.syncStateQueries

    fun list(kind: ExclusionKind): List<String> = settings.selectExclusionsByKind(kind.name).executeAsList()

    fun flow(kind: ExclusionKind): Flow<List<String>> = settings.selectExclusionsByKind(kind.name).asFlow().mapToList(dispatcher)

    /** Distinct Folders of the local Notes, sorted. */
    fun knownFolders(): Flow<List<String>> = notes.selectDistinctCategories().asFlow().mapToList(dispatcher)

    /**
     * Adds [path] (normalized); returns the stored path, or null when it is blank or already listed.
     * A Hidden folder hides its Notes at once, in the same transaction. An Unsynced one waits for the pull.
     * Either way the Folder refresh ETags (`list:<folder>`) are dropped, so the next refresh is a full 200.
     */
    fun add(
        kind: ExclusionKind,
        path: String,
    ): String? {
        val p = normalizeFolderPath(path) ?: return null
        return db.transactionWithResult {
            if (p in list(kind)) return@transactionWithResult null
            settings.insertExclusion(p, kind.name)
            if (kind == ExclusionKind.HIDDEN) notes.recomputeHidden()
            syncState.deleteFolderKeys()
            p
        }
    }

    /** Removes [path]. A Hidden folder shows its Notes again at once, in the same transaction; the Folder ETags go. */
    fun remove(
        kind: ExclusionKind,
        path: String,
    ) = db.transaction {
        settings.deleteExclusion(path, kind.name)
        if (kind == ExclusionKind.HIDDEN) notes.recomputeHidden()
        syncState.deleteFolderKeys()
    }

    /** Local Notes in [path] or below it. */
    fun notesUnder(path: String): Int = notes.countUnderPrefix(path).executeAsOne().toInt()

    /** Local Notes under [path] with edits not yet pushed; they survive an Unsynced exclusion. */
    fun pendingUnder(path: String): Int = notes.countPendingUnderPrefix(path).executeAsOne().toInt()
}
