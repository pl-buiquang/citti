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
    db: CittiDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val settings = db.settingQueries
    private val notes = db.noteQueries

    fun list(kind: ExclusionKind): List<String> = settings.selectExclusionsByKind(kind.name).executeAsList()

    fun flow(kind: ExclusionKind): Flow<List<String>> = settings.selectExclusionsByKind(kind.name).asFlow().mapToList(dispatcher)

    /** Distinct Folders of the local Notes, sorted. */
    fun knownFolders(): Flow<List<String>> = notes.selectDistinctCategories().asFlow().mapToList(dispatcher)

    /** Adds [path] (normalized); returns the stored path, or null when it is blank or already listed. */
    fun add(
        kind: ExclusionKind,
        path: String,
    ): String? {
        val p = normalizeFolderPath(path) ?: return null
        if (p in list(kind)) return null
        settings.insertExclusion(p, kind.name)
        return p
    }

    fun remove(
        kind: ExclusionKind,
        path: String,
    ) = settings.deleteExclusion(path, kind.name)

    /** Local Notes in [path] or below it. */
    fun notesUnder(path: String): Int = notes.countUnderPrefix(path).executeAsOne().toInt()

    /** Local Notes under [path] with edits not yet pushed; they survive an Unsynced exclusion. */
    fun pendingUnder(path: String): Int = notes.countPendingUnderPrefix(path).executeAsOne().toInt()
}
