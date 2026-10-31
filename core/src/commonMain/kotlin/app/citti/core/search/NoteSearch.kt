package app.citti.core.search

import app.cash.sqldelight.Query
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.citti.core.db.CittiDatabase
import app.citti.core.note.NoteBadge
import app.citti.core.note.noteBadgeOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** One Search result. [hidden] Notes are found too; the UI gives them the Hidden badge. */
data class SearchHit(
    val localId: Long,
    val title: String,
    val folder: String,
    val excerpt: String?,
    val favorite: Boolean,
    val modified: Long,
    val hidden: Boolean,
    val badge: NoteBadge?,
    val tier: Int,
)

/**
 * Full-text search over every synced Note through `note_fts` (ADR 0007), Hidden folders included.
 *
 * The hit query is not in `Note.sq`: SQLDelight 2.4.1 rejects `matchinfo()`, so it runs through
 * [SqlDriver.executeQuery], wrapped in a [Query] that listens to the `note` table like the generated ones.
 */
class NoteSearch(
    private val driver: SqlDriver,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val notes = CittiDatabase(driver).noteQueries

    /** Every hit, sorted by tier then modified desc. Re-emits when `note` changes. */
    fun hits(query: FtsQuery): Flow<List<SearchHit>> =
        SearchNotesQuery(driver, query.match) { cursor -> cursor.toHit(query.phrases) }
            .asFlow()
            .mapToList(dispatcher)
            .map { hits -> hits.sortedWith(HIT_ORDER) }

    /** Snippets for [ids] (one page). The map leaves out ids whose snippet is empty; the UI falls back to the Excerpt. */
    suspend fun snippets(
        query: FtsQuery,
        ids: List<Long>,
    ): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        return withContext(dispatcher) {
            notes
                .searchSnippets(query.match, ids)
                .executeAsList()
                .mapNotNull { row -> row.snippet?.takeIf { it.isNotBlank() }?.let { row.local_id to it } }
                .toMap()
        }
    }

    private class SearchNotesQuery<out T : Any>(
        private val driver: SqlDriver,
        private val match: String,
        mapper: (SqlCursor) -> T,
    ) : Query<T>(mapper) {
        override fun addListener(listener: Listener) {
            driver.addListener("note", listener = listener)
        }

        override fun removeListener(listener: Listener) {
            driver.removeListener("note", listener = listener)
        }

        override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> =
            driver.executeQuery(null, SEARCH_NOTES, mapper, 1) { bindString(0, match) }

        override fun toString(): String = "NoteSearch:searchNotes"
    }

    private companion object {
        // Hidden is NOT filtered: Search finds Hidden Notes. DELETED rows stay in the index and are dropped here.
        const val SEARCH_NOTES =
            "SELECT note.local_id, note.title, note.category, note.excerpt, note.favorite, note.modified, " +
                "note.hidden, note.sync_state, note.conflict_of, matchinfo(note_fts, 'x') " +
                "FROM note_fts JOIN note ON note.local_id = note_fts.docid " +
                "WHERE note_fts MATCH ? AND note.sync_state != 'DELETED'"

        val HIT_ORDER = compareBy<SearchHit> { it.tier }.thenByDescending { it.modified }.thenByDescending { it.localId }

        fun SqlCursor.toHit(phrases: Int) =
            SearchHit(
                localId = getLong(0)!!,
                title = getString(1)!!,
                folder = getString(2)!!,
                excerpt = getString(3),
                favorite = getLong(4)!! != 0L,
                modified = getLong(5)!!,
                hidden = getLong(6)!! != 0L,
                badge = noteBadgeOf(conflictOf = getLong(8), syncState = getString(7)!!),
                tier = tierOf(getBytes(9) ?: ByteArray(0), phrases),
            )
    }
}
