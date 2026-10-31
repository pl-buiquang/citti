package app.citti.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.db.CittiDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoteQueriesTest {
    private val db: CittiDatabase =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let {
            CittiDatabase.Schema.create(it)
            CittiDatabase(it)
        }
    private val notes = db.noteQueries

    private fun server(
        id: Long,
        etag: String = "e$id",
        title: String = "t$id",
        category: String = "",
        favorite: Boolean = false,
        modified: Long = id,
        hidden: Boolean = false,
    ) = notes.upsertFromServer(id, etag, title, category, favorite, modified, false, hidden)

    @Test
    fun listHidesHiddenNotesButStillStoresThem() {
        server(1)
        server(2, category = "secret", hidden = true)
        assertEquals(listOf(1L), notes.selectAllForList().executeAsList().mapNotNull { it.remote_id })
        assertEquals(2, notes.countNotes().executeAsOne())
    }

    @Test
    fun distinctCategoriesAndPrefixCounts() {
        server(1)
        server(2, category = "a/b")
        server(3, category = "a/b")
        server(4, category = "a/bc")
        assertEquals(listOf("a/b", "a/bc"), notes.selectDistinctCategories().executeAsList())
        assertEquals(2, notes.countUnderPrefix("a/b").executeAsOne())
        assertEquals(0, notes.countPendingUnderPrefix("a/b").executeAsOne())
    }

    @Test
    fun upsertIsIdempotentAndKeepsLocalId() {
        server(1)
        val first = notes.selectByRemoteId(1).executeAsOne()
        server(1)
        server(1)
        assertEquals(1, notes.countNotes().executeAsOne())
        assertEquals(first, notes.selectByRemoteId(1).executeAsOne())
    }

    @Test
    fun upsertUpdatesMetadataAndClearsContentOnlyWhenEtagChanges() {
        server(1)
        val id = notes.selectByRemoteId(1).executeAsOne().local_id
        notes.updateContent("body", "e1", id)
        server(1, title = "renamed")
        assertEquals("body", notes.selectByRemoteId(1).executeAsOne().content)
        assertEquals("renamed", notes.selectByRemoteId(1).executeAsOne().title)
        server(1, etag = "e1b")
        assertNull(notes.selectByRemoteId(1).executeAsOne().content)
    }

    @Test
    fun upsertDoesNotOverwriteUnsyncedChanges() {
        server(1)
        val id = notes.selectByRemoteId(1).executeAsOne().local_id
        notes.markDirty("mine", 99, id)
        server(1, etag = "other", title = "server")
        val note = notes.selectByRemoteId(1).executeAsOne()
        assertEquals("mine", note.content)
        assertEquals("t1", note.title)
    }

    @Test
    fun remoteIdsAndNeedingContent() {
        server(1)
        server(2)
        server(3)
        val id1 = notes.selectByRemoteId(1).executeAsOne().local_id
        val id3 = notes.selectByRemoteId(3).executeAsOne().local_id
        notes.updateContent("x", "e1", id1)
        notes.markDeleted(id3)
        notes.insertNew("local", "", "c", 5, null)
        assertEquals(
            listOf(1L, 2L, 3L),
            notes
                .selectAllRemoteIds()
                .executeAsList()
                .filterNotNull()
                .sorted(),
        )
        assertEquals(listOf(2L), notes.selectNeedingContent().executeAsList().map { it.remote_id })
        assertEquals(3, notes.countNotes().executeAsOne())
        assertEquals(2, notes.countNotesWithContent().executeAsOne())
    }

    @Test
    fun dirtySelectionAndTransitions() {
        server(1)
        server(2)
        server(3)
        val id1 = notes.selectByRemoteId(1).executeAsOne().local_id
        val id2 = notes.selectByRemoteId(2).executeAsOne().local_id
        notes.markDirty("edit", 10, id1)
        notes.markDeleted(id2)
        notes.insertNew("new", "a", "body", 11, null)
        val states = notes.selectDirty().executeAsList().map { it.sync_state }
        assertEquals(listOf("DIRTY", "DELETED", "NEW"), states)
        val newId =
            notes
                .selectDirty()
                .executeAsList()
                .last()
                .local_id
        notes.markDirty("more", 12, newId)
        assertEquals("NEW", notes.selectByLocalId(newId).executeAsOne().sync_state)
        notes.markDirty("ignored", 13, id2)
        assertEquals("DELETED", notes.selectByLocalId(id2).executeAsOne().sync_state)
        notes.markSynced(77, "e77", 14, newId)
        assertEquals(77L, notes.selectByLocalId(newId).executeAsOne().remote_id)
        assertEquals(2, notes.selectDirty().executeAsList().size)
    }

    @Test
    fun conflictCopyKeepsOrigin() {
        server(1)
        val id = notes.selectByRemoteId(1).executeAsOne().local_id
        notes.insertNew("t1 (conflict)", "", "mine", 5, id)
        val copyId = notes.lastInsertedLocalId().executeAsOne()
        assertEquals(id, notes.selectByLocalId(copyId).executeAsOne().conflict_of)
    }

    @Test
    fun deleteByCategoryPrefixMatchesFolderAndSubfoldersOnly() {
        listOf("a", "a/b", "a/b/c", "a/bc", "ab", "_work", "xwork", "").forEachIndexed { i, c ->
            server(i + 1L, category = c)
        }
        notes.deleteByCategoryPrefix("a/b")
        val left =
            notes
                .selectAllForList()
                .executeAsList()
                .map { it.category }
                .toSet()
        assertEquals(setOf("a", "a/bc", "ab", "_work", "xwork", ""), left)
        notes.deleteByCategoryPrefix("_work")
        assertTrue(notes.selectAllForList().executeAsList().any { it.category == "xwork" })
        assertFalse(notes.selectAllForList().executeAsList().any { it.category == "_work" })
    }

    @Test
    fun deleteByLocalId() {
        server(1)
        notes.deleteByLocalId(notes.selectByRemoteId(1).executeAsOne().local_id)
        assertEquals(0, notes.countNotes().executeAsOne())
    }

    @Test
    fun listOrdersFavoritesFirstThenNewestAndSkipsDeleted() {
        server(1, modified = 100)
        server(2, modified = 300)
        server(3, modified = 50, favorite = true)
        server(4, modified = 400)
        notes.markDeleted(notes.selectByRemoteId(4).executeAsOne().local_id)
        assertEquals(listOf(3L, 2L, 1L), notes.selectAllForList().executeAsList().map { it.remote_id })
    }

    @Test
    fun syncStateRoundTrip() {
        val q = db.syncStateQueries
        assertNull(q.select("list-plain").executeAsOneOrNull())
        q.upsert("list-plain", "\"abc\"", 10, null)
        q.upsert("list-plain", "\"def\"", 20, "cur")
        q.upsert("list-pruned", "\"x\"", 5, null)
        val row = q.select("list-plain").executeAsOne()
        assertEquals("\"def\"", row.etag)
        assertEquals(20L, row.last_modified_seen)
        assertEquals("cur", row.chunk_cursor)
        q.clear("list-plain")
        assertNull(q.select("list-plain").executeAsOneOrNull())
        assertEquals("\"x\"", q.select("list-pruned").executeAsOne().etag)
        q.clearAll()
        assertNull(q.select("list-pruned").executeAsOneOrNull())
    }

    @Test
    fun settingsAndExclusions() {
        val q = db.settingQueries
        q.upsertSetting("interval", "15")
        q.upsertSetting("interval", "30")
        assertEquals("30", q.selectSetting("interval").executeAsOne())
        q.deleteSetting("interval")
        assertNull(q.selectSetting("interval").executeAsOneOrNull())

        q.insertExclusion("b", "UNSYNCED")
        q.insertExclusion("a", "UNSYNCED")
        q.insertExclusion("a", "UNSYNCED")
        q.insertExclusion("a", "HIDDEN")
        assertEquals(listOf("a", "b"), q.selectExclusionsByKind("UNSYNCED").executeAsList())
        assertEquals(listOf("a"), q.selectExclusionsByKind("HIDDEN").executeAsList())
        q.deleteExclusion("a", "UNSYNCED")
        assertEquals(listOf("b"), q.selectExclusionsByKind("UNSYNCED").executeAsList())
        assertEquals(listOf("a"), q.selectExclusionsByKind("HIDDEN").executeAsList())
    }

    @Test
    fun migrationOneUpgradesAMetaOnlyDatabase() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "CREATE TABLE meta (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)", 0)
        driver.execute(null, "INSERT INTO meta(key, value) VALUES ('schema', '1')", 0)
        CittiDatabase.Schema.migrate(driver, 1, CittiDatabase.Schema.version)
        val upgraded = CittiDatabase(driver)
        assertEquals("1", upgraded.metaQueries.selectValue("schema").executeAsOne())
        upgraded.noteQueries.insertNew("t", "", "c", 1, null)
        upgraded.syncStateQueries.upsert("k", null, 0, null)
        upgraded.settingQueries.insertExclusion("p", "HIDDEN")
        assertEquals(1, upgraded.noteQueries.countNotes().executeAsOne())
        assertEquals(2, CittiDatabase.Schema.version)
    }
}
