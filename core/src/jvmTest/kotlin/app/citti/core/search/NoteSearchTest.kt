package app.citti.core.search

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.db.CittiDatabase
import app.citti.core.insertNew
import app.citti.core.markDirty
import app.citti.core.note.NoteBadge
import app.citti.core.note.excerptOf
import app.citti.core.updateContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoteSearchTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { CittiDatabase.Schema.create(it) }
    private val db = CittiDatabase(driver)
    private val notes = db.noteQueries
    private val search = NoteSearch(driver, Dispatchers.Unconfined)

    /** A synced Note with [content]; returns its local id. */
    private fun note(
        title: String,
        content: String? = null,
        category: String = "",
        modified: Long = 1,
        hidden: Boolean = false,
        remoteId: Long = nextRemoteId++,
    ): Long {
        notes.upsertFromServer(remoteId, "e$remoteId", title, category, false, modified, false, hidden)
        val id = notes.selectByRemoteId(remoteId).executeAsOne().local_id
        if (content != null) notes.updateContent(content, "e$remoteId", id)
        return id
    }

    private var nextRemoteId = 1L

    private suspend fun hits(raw: String): List<SearchHit> = toFtsMatch(raw)?.let { search.hits(it).first() }.orEmpty()

    private suspend fun ids(raw: String): List<Long> = hits(raw).map { it.localId }

    private fun ftsRows(): Long =
        driver
            .executeQuery(
                null,
                "SELECT count(*) FROM note_fts_docsize",
                { c -> QueryResult.Value(c.next().value.let { c.getLong(0)!! }) },
                0,
            ).value

    @Test
    fun findsByTitleContentAndPath() =
        runTest {
            val t = note("Groceries")
            val c = note("Monday", content = "buy groceries later")
            val p = note("Other", category = "groceries/2026")
            note("Unrelated", content = "nothing here")
            assertEquals(setOf(t, c, p), ids("groceries").toSet())
        }

    @Test
    fun prefixMatch() =
        runTest {
            val id = note("etag handling")
            assertEquals(listOf(id), ids("eta"))
            // Prefix only: a word inside another word is not found.
            assertEquals(emptyList(), ids("tag"))
        }

    @Test
    fun accentAndCaseInsensitive() =
        runTest {
            val id = note("Été à Paris")
            assertEquals(listOf(id), ids("ete"))
            assertEquals(listOf(id), ids("ÉTÉ"))
            assertEquals(listOf(id), ids("paris"))
        }

    @Test
    fun wordsAreAnded() =
        runTest {
            val both = note("sync engine")
            note("sync only")
            note("engine only")
            assertEquals(listOf(both), ids("sync eng"))
        }

    @Test
    fun phraseInQuotes() =
        runTest {
            val phrase = note("Day", content = "une journée d'été à Paris")
            note("Split", content = "journée et puis été")
            assertEquals(listOf(phrase), ids("\"journee d ete\""))
            // A phrase is not a prefix.
            assertEquals(emptyList(), ids("\"journ\""))
        }

    @Test
    fun operatorsAreLiteral() =
        runTest {
            val a = note("alpha", content = "x")
            note("beta")
            val t = note("title:x")
            // As operators, these would find alpha or beta, nothing, and alpha by its title column.
            assertEquals(emptyList(), ids("alpha OR beta"))
            assertEquals(listOf(a), ids("alpha -x"))
            assertEquals(listOf(t), ids("title:x"))
            assertEquals(emptyList(), ids("\""))
            assertEquals(listOf(a), ids("alpha \""))
            assertEquals(emptyList(), ids("NEAR alpha"))
            assertEquals(listOf(a), ids("(alpha)"))
            assertEquals(emptyList(), ids("alpha NOT x"))
        }

    @Test
    fun tiersOrderTitleThenContentThenPath() =
        runTest {
            val pathOld = note("P1", category = "plan", modified = 50)
            val contentOld = note("C1", content = "the plan", modified = 10)
            val titleOld = note("plan B", modified = 1)
            val contentNew = note("C2", content = "plan again", modified = 40)
            val titleNew = note("Plan A", modified = 30)
            val hits = hits("plan")
            assertEquals(listOf(titleNew, titleOld, contentNew, contentOld, pathOld), hits.map { it.localId })
            assertEquals(listOf(1, 1, 2, 2, 3), hits.map { it.tier })
        }

    @Test
    fun tierNeedsEveryWord() =
        runTest {
            val mixed = note("plan", content = "weekly")
            val path = note("plan", category = "weekly")
            assertEquals(listOf(mixed to 2, path to 3), hits("plan weekly").map { it.localId to it.tier })
        }

    @Test
    fun oneCharSearchesTitlesOnly() =
        runTest {
            val title = note("apples")
            note("Other", content = "apples")
            note("Third", category = "apples")
            val hits = hits("a")
            assertEquals(listOf(title), hits.map { it.localId })
            assertEquals(1, hits.single().tier)
        }

    @Test
    fun hiddenNotesAreFoundAndFlagged() =
        runTest {
            val visible = note("secret plans")
            val hidden = note("secret diary", category = "private", hidden = true)
            val hits = hits("secret").associateBy { it.localId }
            assertEquals(setOf(visible, hidden), hits.keys)
            assertEquals(true, hits.getValue(hidden).hidden)
            assertEquals(false, hits.getValue(visible).hidden)
        }

    @Test
    fun deletedNotesAreNotFound() =
        runTest {
            val gone = note("ghost")
            notes.markDeleted(gone)
            assertEquals(emptyList(), ids("ghost"))
        }

    @Test
    fun nullContentMatchesTitleAndPath() =
        runTest {
            val id = note("Waiting", category = "inbox")
            assertEquals(listOf(id), ids("waiting"))
            assertEquals(listOf(id), ids("inbox"))
        }

    @Test
    fun hitsCarryRowFieldsAndBadges() =
        runTest {
            val synced = note("row one", content = "# row one\n\nfirst line", category = "a/b", modified = 9)
            val dirty = note("row two", content = "x")
            notes.markDirty("edited", 20, dirty)
            notes.insertNew("row three", "", "copy", 21, synced)
            val byId = hits("row").associateBy { it.localId }
            val first = byId.getValue(synced)
            assertEquals(SearchHit(synced, "row one", "a/b", "first line", false, 9, false, null, 1), first)
            assertEquals(NoteBadge.Pending, byId.getValue(dirty).badge)
            assertEquals(NoteBadge.Conflict, byId.values.single { it.title == "row three" }.badge)
        }

    @Test
    fun hitsReEmitWhenNotesChange() =
        runTest {
            note("first match")
            val query = toFtsMatch("match")!!
            val emissions = mutableListOf<List<SearchHit>>()
            val job = launch(Dispatchers.Unconfined) { search.hits(query).take(2).toList(emissions) }
            yield()
            note("second match")
            job.join()
            assertEquals(listOf(1, 2), emissions.map { it.size })
        }

    @Test
    fun indexFollowsUpsertUpdateContentMarkDirtyReplaceAndDelete() =
        runTest {
            // upsertFromServer changing the title
            val id = note("old title", remoteId = 1)
            note("new title", remoteId = 1)
            assertEquals(emptyList(), ids("old"))
            assertEquals(listOf(id), ids("new"))

            // updateContent
            notes.updateContent("downloaded words", "e1", id)
            assertEquals(listOf(id), ids("downloaded"))

            // markDirty
            notes.markDirty("typed words", 5, id)
            assertEquals(emptyList(), ids("downloaded"))
            assertEquals(listOf(id), ids("typed"))

            // replaceWithServer
            notes.replaceWithServer(
                "server title",
                "srv",
                "server words",
                excerptOf("server words", "server title"),
                false,
                "e2",
                6,
                false,
                id,
            )
            assertEquals(emptyList(), ids("typed"))
            assertEquals(listOf(id), ids("server"))
            assertEquals(listOf(id), ids("srv"))

            // insertNew
            notes.insertNew("fresh", "", "brand new", 7, null)
            assertEquals(1, ids("brand").size)

            // deleteByLocalId
            notes.deleteByLocalId(id)
            assertEquals(emptyList(), ids("server"))
        }

    @Test
    fun indexEmptyAfterSignOutWipe() =
        runTest {
            note("one", content = "common")
            note("two", content = "common")
            assertEquals(2, ids("common").size)
            assertEquals(2, ftsRows())
            db.accountQueries.deleteAllNotes()
            assertEquals(emptyList(), ids("common"))
            assertEquals(0, ftsRows())
        }

    /** FTS4 writes a new index segment on every insert or delete, until it merges 16 of them. */
    private fun segments(): Long =
        driver
            .executeQuery(
                null,
                "SELECT count(*) FROM note_fts_segdir",
                { c -> QueryResult.Value(c.next().value.let { c.getLong(0)!! }) },
                0,
            ).value

    @Test
    fun metadataWritesDoNotReindex() =
        runTest {
            notes.insertNew("T", "c", "body", 5, null)
            val id = notes.lastInsertedLocalId().executeAsOne()
            val before = segments()
            notes.setExcerpt("other", id)
            notes.markDeleted(id)
            db.settingQueries.insertExclusion("c", "HIDDEN")
            notes.recomputeHidden()
            assertEquals(before, segments())
            // whereas a content write does re-index
            notes.updateContent("other", "e8", id)
            assertTrue(segments() > before)
        }

    /** markSynced and an unchanged upsertFromServer name title and category in their SET, with the same values. */
    @Test
    fun markSyncedDoesNotReindex() =
        runTest {
            notes.insertNew("T", "c", "body", 5, null)
            val id = notes.lastInsertedLocalId().executeAsOne()
            val before = segments()
            notes.markSynced(7, "e7", "T", "T", "c", "c", "body", 100, id)
            assertEquals(before, segments())
            notes.upsertFromServer(7, "e7", "T", "c", false, 100, false, false)
            assertEquals(before, segments())
            assertEquals(listOf(id), ids("body"))
            assertEquals(listOf(id), ids("T"))
        }

    @Test
    fun snippetsHighlightMatchesAndFallBackWhenContentNull() =
        runTest {
            val withText = note("Notes", content = "Before the word sync and after it")
            val noText = note("sync later")
            val query = toFtsMatch("sync")!!
            val snippets = search.snippets(query, listOf(withText, noText))
            assertEquals(setOf(withText), snippets.keys)
            assertTrue("\u0002sync\u0003" in snippets.getValue(withText), snippets.getValue(withText))
            assertEquals(emptyMap(), search.snippets(query, emptyList()))
        }
}
