package app.citti.ui.search

import app.citti.core.search.FtsQuery
import app.citti.core.search.SearchHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun hit(id: Long) =
    SearchHit(id, "t$id", folder = "", excerpt = "e$id", favorite = false, modified = id, hidden = id == 2L, badge = null, tier = 1)

/** A fake NoteSearch: [results] per MATCH string, [slow] matches never finish until cancelled. */
private class FakeSearch(
    val results: Map<String, List<SearchHit>> = emptyMap(),
    val slow: Set<String> = emptySet(),
) {
    val queries = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    val snippetCalls = mutableListOf<List<Long>>()

    fun hits(q: FtsQuery): Flow<List<SearchHit>> =
        flow {
            queries += q.match
            try {
                if (q.match in slow) awaitCancellation()
                emit(results[q.match].orEmpty())
            } catch (e: CancellationException) {
                cancelled += q.match
                throw e
            }
        }

    suspend fun snippets(
        q: FtsQuery,
        ids: List<Long>,
    ): Map<Long, String> {
        snippetCalls += ids
        delay(10)
        return ids.associateWith { "s$it" }
    }

    fun controller(
        scope: CoroutineScope,
        initial: String = "",
    ) = SearchController(scope, ::hits, ::snippets, initial)
}

/** Runs past the debounce and the fake delays. advanceUntilIdle would not: it ignores backgroundScope work. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.settle() {
    advanceTimeBy(1_000)
    runCurrent()
}

@OptIn(ExperimentalCoroutinesApi::class)
class SearchControllerTest {
    @Test
    fun typingWithinTheDebounceRunsOneQuery() =
        runTest {
            val f = FakeSearch(mapOf("\"abc*\"" to listOf(hit(1))))
            val c = f.controller(backgroundScope)
            c.onQuery("a")
            advanceTimeBy(50)
            c.onQuery("ab")
            advanceTimeBy(50)
            c.onQuery("abc")
            runCurrent()
            assertEquals("abc", c.state.value.query)
            assertTrue(c.state.value.searching)
            advanceTimeBy(SearchController.DEBOUNCE_MS - 1)
            runCurrent()
            assertEquals(emptyList(), f.queries)
            advanceTimeBy(2)
            runCurrent()
            assertEquals(listOf("\"abc*\""), f.queries)
            assertEquals(
                listOf(1L),
                c.state.value.hits
                    .map { it.localId },
            )
            assertEquals(1, c.state.value.count)
            assertFalse(c.state.value.searching)
        }

    @Test
    fun aNewQueryCancelsTheOldOne() =
        runTest {
            val f = FakeSearch(mapOf("\"new*\"" to listOf(hit(1), hit(2))), slow = setOf("\"old*\""))
            val c = f.controller(backgroundScope)
            c.onQuery("old")
            advanceTimeBy(SearchController.DEBOUNCE_MS + 1)
            runCurrent()
            assertEquals(listOf("\"old*\""), f.queries)
            c.onQuery("new")
            settle()
            assertEquals(listOf("\"old*\""), f.cancelled)
            assertEquals(
                listOf(1L, 2L),
                c.state.value.hits
                    .map { it.localId },
            )
            assertTrue(
                c.state.value.hits[1]
                    .hidden,
            )
        }

    @Test
    fun snippetPagesLoadOnceEach() =
        runTest {
            val many = (1L..120L).map(::hit)
            val f = FakeSearch(mapOf("\"word*\"" to many))
            val c = f.controller(backgroundScope, initial = "word")
            settle()
            assertEquals(120, c.state.value.count)
            c.onVisible(0, 10)
            c.onVisible(3, 20)
            settle()
            assertEquals(listOf((1L..50L).toList()), f.snippetCalls)
            assertEquals(
                "s1",
                c.state.value.hits[0]
                    .snippet,
            )
            assertEquals(
                null,
                c.state.value.hits[50]
                    .snippet,
            )
            // Rows 45..60 cover pages 0 and 1: only page 1 loads.
            c.onVisible(45, 60)
            c.onVisible(55, 99)
            settle()
            assertEquals(listOf((1L..50L).toList(), (51L..100L).toList()), f.snippetCalls)
            assertEquals(
                "s51",
                c.state.value.hits[50]
                    .snippet,
            )
        }

    @Test
    fun aNewQueryDropsTheSnippets() =
        runTest {
            val f = FakeSearch(mapOf("\"one*\"" to listOf(hit(1)), "\"two*\"" to listOf(hit(1))))
            val c = f.controller(backgroundScope, initial = "one")
            settle()
            c.onVisible(0, 0)
            settle()
            assertEquals(
                "s1",
                c.state.value.hits
                    .single()
                    .snippet,
            )
            c.onQuery("two")
            settle()
            assertEquals(
                null,
                c.state.value.hits
                    .single()
                    .snippet,
            )
            c.onVisible(0, 0)
            settle()
            assertEquals(2, f.snippetCalls.size)
        }

    @Test
    fun aSyncReloadsTheSnippetsOfTheLastRange() =
        runTest {
            val f = FakeSearch()
            val live = MutableStateFlow(listOf(hit(1), hit(3)))
            val c = SearchController(backgroundScope, { live }, f::snippets, "word")
            settle()
            c.onVisible(0, 1)
            settle()
            assertEquals(listOf(listOf(1L, 3L)), f.snippetCalls)
            // A sync inserts Note 2 inside the visible range and changes Note 3; the screen reports nothing new.
            live.value = listOf(hit(1), hit(2), hit(3).copy(modified = 30))
            settle()
            assertEquals(listOf(listOf(1L, 3L), listOf(2L, 3L)), f.snippetCalls)
            assertEquals(
                listOf("s1", "s2", "s3"),
                c.state.value.hits
                    .map { it.snippet },
            )
            // The same hits again load nothing.
            live.value = live.value.toList()
            settle()
            assertEquals(2, f.snippetCalls.size)
        }

    @Test
    fun clearEmptiesAtOnce() =
        runTest {
            val f = FakeSearch(mapOf("\"word*\"" to listOf(hit(1))))
            val c = f.controller(backgroundScope, initial = "word")
            settle()
            assertEquals(1, c.state.value.count)
            c.clear()
            runCurrent()
            assertEquals(SearchUiState(), c.state.value)
            settle()
            assertEquals(SearchUiState(), c.state.value)
            assertEquals(listOf("\"word*\""), f.queries)
        }
}
