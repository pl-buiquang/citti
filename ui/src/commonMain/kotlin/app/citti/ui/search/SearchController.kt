package app.citti.ui.search

import app.citti.core.log.SyncLog
import app.citti.core.search.FtsQuery
import app.citti.core.search.SearchHit
import app.citti.core.search.toFtsMatch
import app.citti.ui.browse.NoteRowItem
import app.citti.ui.components.toRowBadge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * [hits] are the rows of the last finished search; [count] is their number. [searching] is true while
 * [query] has not been searched yet (the debounce or the query is still running).
 */
data class SearchUiState(
    val query: String = "",
    val hits: List<NoteRowItem> = emptyList(),
    val count: Int = 0,
    val searching: Boolean = false,
)

/**
 * State holder of one Search route entry. [hits] and [snippets] are `NoteSearch.hits` and `NoteSearch.snippets`.
 *
 * The query is searched 150 ms after the last keystroke, and a new query cancels the running one. Snippets are
 * loaded per page of [PAGE] rows, once per page, for the pages the screen reports in [onVisible]; a new query
 * drops them. When the hits of the same query change (a sync), the last reported range is loaded again: new
 * ids get their snippets, and a Note whose `modified` changed has its snippet loaded again. [log] gets the timings (counts and milliseconds only, never the query).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchController(
    private val scope: CoroutineScope,
    hits: (FtsQuery) -> Flow<List<SearchHit>>,
    private val snippets: suspend (FtsQuery, List<Long>) -> Map<Long, String>,
    initialQuery: String,
    private val log: SyncLog = SyncLog.None,
) {
    private val query = MutableStateFlow(initialQuery)

    /** The hits of one searched text. [fts] is null when nothing in [text] is searchable. */
    private class Results(
        val text: String,
        val fts: FtsQuery?,
        val hits: List<SearchHit>,
    )

    /** The snippets loaded for [fts], and the ids already asked for, with their `modified` when asked. */
    private class Snippets(
        val fts: FtsQuery?,
        val byId: Map<Long, String> = emptyMap(),
        val asked: Map<Long, Long> = emptyMap(),
    )

    private val snippetState = MutableStateFlow(Snippets(null))

    // The last range reported by onVisible, loaded again when the hits of the same query change.
    private val lastRange = MutableStateFlow<Pair<Int, Int>?>(null)

    // The latest results; onVisible reads them to know which ids a page holds.
    private val results: StateFlow<Results?> =
        query
            .debounce(DEBOUNCE_MS)
            .distinctUntilChanged()
            .flatMapLatest { text ->
                val fts = toFtsMatch(text)
                if (fts == null) {
                    flowOf(Results(text, null, emptyList()))
                } else {
                    val started = TimeSource.Monotonic.markNow()
                    var first = true
                    hits(fts).map { found ->
                        if (first) {
                            first = false
                            log.info("search hits=${found.size} ms=${started.elapsedNow().inWholeMilliseconds}")
                        }
                        Results(text, fts, found)
                    }
                }
            }.onEach { r ->
                // A new query drops the snippets of the previous one; the same query drops those of changed Notes.
                var same = false
                snippetState.update { s ->
                    if (s.fts != r.fts) return@update Snippets(r.fts)
                    same = true
                    val changed = r.hits.filter { h -> s.asked[h.localId].let { it != null && it != h.modified } }
                    if (changed.isEmpty()) {
                        s
                    } else {
                        val ids = changed.map { it.localId }.toSet()
                        Snippets(s.fts, s.byId - ids, s.asked - ids)
                    }
                }
                val range = lastRange.value
                if (same && range != null) load(r, range.first, range.second)
            }.stateIn(scope, SharingStarted.Eagerly, null)

    val state: StateFlow<SearchUiState> =
        combine(query, results, snippetState) { text, r, snips ->
            if (text.isBlank()) {
                SearchUiState(query = text)
            } else {
                val shown = r?.hits.orEmpty()
                val byId = if (snips.fts == r?.fts) snips.byId else emptyMap()
                SearchUiState(
                    query = text,
                    hits = shown.map { it.toRowItem(byId[it.localId]) },
                    count = shown.size,
                    searching = r?.text != text,
                )
            }
        }.stateIn(scope, SharingStarted.Eagerly, SearchUiState(query = initialQuery, searching = initialQuery.isNotBlank()))

    fun onQuery(text: String) {
        query.value = text
    }

    /** The rows [firstIndex]..[lastIndex] are on screen: loads the snippets of the pages they cover, once per page. */
    fun onVisible(
        firstIndex: Int,
        lastIndex: Int,
    ) {
        lastRange.value = firstIndex to lastIndex
        load(results.value ?: return, firstIndex, lastIndex)
    }

    private fun load(
        r: Results,
        firstIndex: Int,
        lastIndex: Int,
    ) {
        val fts = r.fts ?: return
        if (r.hits.isEmpty() || firstIndex > lastIndex) return
        val last = lastIndex.coerceAtMost(r.hits.lastIndex)
        for (page in (firstIndex.coerceAtLeast(0) / PAGE)..(last / PAGE)) {
            val from = page * PAGE
            val rows = r.hits.subList(from, minOf(from + PAGE, r.hits.size))
            var wanted = emptyList<SearchHit>()
            snippetState.update { s ->
                if (s.fts != fts) return@update s
                wanted = rows.filter { it.localId !in s.asked }
                if (wanted.isEmpty()) s else Snippets(s.fts, s.byId, s.asked + wanted.associate { it.localId to it.modified })
            }
            if (wanted.isEmpty()) continue
            val ask = wanted.map { it.localId }
            scope.launch {
                val started = TimeSource.Monotonic.markNow()
                val found = snippets(fts, ask)
                if (page == 0) log.info("search snippets=${found.size} ms=${started.elapsedNow().inWholeMilliseconds}")
                snippetState.update { s -> if (s.fts == fts) Snippets(s.fts, s.byId + found, s.asked) else s }
            }
        }
    }

    /** The ✕ of the search field. */
    fun clear() {
        query.value = ""
    }

    companion object {
        const val DEBOUNCE_MS = 150L

        /** Rows per snippet page. */
        const val PAGE = 50
    }
}

/** A Search row: the Hidden badge where it applies, and the [snippet] when there is one. */
fun SearchHit.toRowItem(snippet: String? = null): NoteRowItem =
    NoteRowItem(
        localId = localId,
        title = title,
        folder = folder,
        excerpt = excerpt,
        favorite = favorite,
        modified = modified,
        badge = badge?.toRowBadge(),
        hidden = hidden,
        snippet = snippet,
    )
