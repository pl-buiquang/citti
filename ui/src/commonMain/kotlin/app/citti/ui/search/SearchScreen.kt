package app.citti.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.citti.ui.TestTags
import app.citti.ui.browse.NoteRowItem
import app.citti.ui.browse.RowDateFormatter
import app.citti.ui.browse.rowDateText
import app.citti.ui.components.EmptyState
import app.citti.ui.components.Gutter
import app.citti.ui.components.NoteRow
import app.citti.ui.components.SearchBar
import app.citti.ui.components.SearchLeading
import app.citti.ui.components.colored
import app.citti.ui.components.rememberHighlighted
import app.citti.ui.resources.Res
import app.citti.ui.resources.search_count
import app.citti.ui.resources.search_empty
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The user's actions on Search. [onOpenNote] null leaves the rows without a tap action. */
class SearchActions(
    val onQuery: (String) -> Unit,
    val onClear: () -> Unit,
    val onVisible: (firstIndex: Int, lastIndex: Int) -> Unit,
    val onOpenNote: ((localId: Long) -> Unit)?,
    val onBack: () -> Unit,
)

/**
 * Search: ← and the focused search field, "N notes", then the hits with their Folder and snippet (the
 * Excerpt when there is none) and the Hidden badge. An empty query shows nothing, or [idleRows] when given (the Note picker). [listState] is the scroll
 * position of this route entry; the rows on screen are reported to [SearchActions.onVisible] for snippets.
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    nowSeconds: () -> Long,
    dates: RowDateFormatter,
    listState: LazyListState,
    actions: SearchActions,
    idleRows: List<NoteRowItem> = emptyList(),
) {
    val now by produceState(nowSeconds()) {
        while (true) {
            delay(30_000)
            value = nowSeconds()
        }
    }
    // The field keeps its own text: a round trip through the controller's StateFlow would drop fast keystrokes.
    var text by rememberSaveable { mutableStateOf(state.query) }
    val onVisible by rememberUpdatedState(actions.onVisible)
    // The hits change with the query, so the visible range is reported again once each query has its hits.
    LaunchedEffect(listState, state.query, state.searching) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) null else visible.first().index to visible.last().index
        }.distinctUntilChanged()
            .collect { range -> if (range != null) onVisible(range.first, range.second) }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(CittiTheme.colors.bg)
            .safeDrawingPadding(),
    ) {
        SearchBar(
            query = text,
            onQueryChange = {
                text = it
                if (it.isEmpty()) actions.onClear() else actions.onQuery(it)
            },
            leading = SearchLeading.Back,
            onLeading = actions.onBack,
            modifier = Modifier.padding(vertical = NotesSpacing.space2),
        )
        val showResults = state.query.isNotBlank() && !(state.searching && state.hits.isEmpty())
        if (showResults && state.hits.isNotEmpty()) {
            Gutter {
                BasicText(
                    pluralStringResource(Res.plurals.search_count, state.count, state.count),
                    modifier = Modifier.padding(vertical = NotesSpacing.space1),
                    style = CittiTheme.typography.meta.colored(muted = true),
                )
            }
        }
        // The hits are drawn from the first row, so the reported indexes are hit indexes.
        LazyColumn(Modifier.weight(1f).testTag(TestTags.SEARCH_RESULTS), state = listState) {
            if (!showResults) {
                if (state.query.isBlank()) items(idleRows, key = { it.localId }) { item -> HitRow(item, now, dates, actions.onOpenNote) }
                return@LazyColumn
            }
            if (state.hits.isEmpty()) {
                item(key = "empty") {
                    EmptyState(stringResource(Res.string.search_empty, state.query.trim()), Modifier.fillParentMaxSize())
                }
                return@LazyColumn
            }
            items(state.hits, key = { it.localId }) { item -> HitRow(item, now, dates, actions.onOpenNote) }
        }
    }
}

@Composable
private fun HitRow(
    item: NoteRowItem,
    now: Long,
    dates: RowDateFormatter,
    onOpenNote: ((Long) -> Unit)?,
) {
    NoteRow(
        title = item.title,
        folder = item.folder,
        excerpt = item.excerpt,
        snippet = item.snippet?.let { rememberHighlighted(it) },
        date = rowDateText(item.modified, now, dates),
        favorite = item.favorite,
        badge = item.badge,
        hidden = item.hidden,
        onClick = onOpenNote?.let { open -> { open(item.localId) } },
    )
}
