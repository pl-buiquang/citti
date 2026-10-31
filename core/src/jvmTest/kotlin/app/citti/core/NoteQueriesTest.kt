package app.citti.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.db.CittiDatabase
import app.citti.core.note.ExcerptBackfill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoteQueriesTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { CittiDatabase.Schema.create(it) }
    private val db: CittiDatabase = CittiDatabase(driver)
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
        assertEquals(listOf("t1"), notes.selectListRows(favoritesOnly = 0).executeAsList().map { it.title })
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
        synced(77, "new", "a", "more", newId)
        assertEquals(77L, notes.selectByLocalId(newId).executeAsOne().remote_id)
        assertEquals(2, notes.selectDirty().executeAsList().size)
    }

    /** markSynced after a push of the sent values, answered with "<title> (2)" in "server/<category>". */
    private fun synced(
        remoteId: Long,
        sentTitle: String,
        sentCategory: String,
        sentContent: String,
        localId: Long,
        sentFavorite: Boolean = false,
    ) = notes.markSynced(
        remoteId = remoteId,
        etag = "e$remoteId",
        sentTitle = sentTitle,
        title = "$sentTitle (2)",
        sentCategory = sentCategory,
        category = "server/$sentCategory",
        sentContent = sentContent,
        sentFavorite = sentFavorite,
        modified = 100,
        localId = localId,
    )

    private fun newNote(): Long {
        notes.insertNew("T", "c", "body", 5, null)
        return notes.lastInsertedLocalId().executeAsOne()
    }

    @Test
    fun markSyncedTakesServerTitleAndCategoryWhenUnchanged() {
        val id = newNote()
        synced(7, "T", "c", "body", id)
        val row = notes.selectByLocalId(id).executeAsOne()
        assertEquals("SYNCED", row.sync_state)
        assertEquals("T (2)", row.title)
        assertEquals("server/c", row.category)
        assertEquals("e7", row.etag)
        assertEquals(100, row.modified)
    }

    @Test
    fun markSyncedKeepsEditMadeDuringPush() {
        val id = newNote()
        notes.markDirty("typed meanwhile", 6, id)
        synced(7, "T", "c", "body", id)
        val row = notes.selectByLocalId(id).executeAsOne()
        assertEquals("DIRTY", row.sync_state)
        assertEquals(7L, row.remote_id)
        assertEquals("e7", row.etag)
        assertEquals("typed meanwhile", row.content)
        assertEquals("T (2)", row.title)
        assertEquals(6, row.modified)
    }

    @Test
    fun markSyncedKeepsFavoriteToggledDuringPush() {
        val id = newNote()
        // No query toggles a Favorite yet (M5 NoteActions), so the test writes it directly.
        driver.execute(null, "UPDATE note SET favorite = 1 WHERE local_id = ?", 1) { bindLong(0, id) }
        synced(7, "T", "c", "body", id, sentFavorite = false)
        val row = notes.selectByLocalId(id).executeAsOne()
        assertEquals("DIRTY", row.sync_state)
        assertEquals(true, row.favorite)
        assertEquals(7L, row.remote_id)
        assertEquals("e7", row.etag)
        assertEquals(5, row.modified)

        synced(7, "T (2)", "server/c", "body", id, sentFavorite = true)
        assertEquals("SYNCED", notes.selectByLocalId(id).executeAsOne().sync_state)
    }

    @Test
    fun markSyncedKeepsLocalTitleAndDelete() {
        // renamed to "Mine" while "T" was in flight
        notes.insertNew("Mine", "c", "body", 5, null)
        val renamed = notes.lastInsertedLocalId().executeAsOne()
        synced(7, "T", "c", "body", renamed)
        val row = notes.selectByLocalId(renamed).executeAsOne()
        assertEquals("Mine", row.title)
        assertEquals("server/c", row.category)
        assertEquals("DIRTY", row.sync_state)

        val deleted = newNote()
        notes.markDeleted(deleted)
        synced(8, "T", "c", "body", deleted)
        val gone = notes.selectByLocalId(deleted).executeAsOne()
        assertEquals("DELETED", gone.sync_state)
        assertEquals(8L, gone.remote_id)
        assertEquals("e8", gone.etag)
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
    fun insertNewInHiddenFolderIsHidden() {
        db.settingQueries.insertExclusion("a/b", "HIDDEN")
        db.settingQueries.insertExclusion("a", "UNSYNCED")
        server(1, category = "a/b", hidden = true)
        val origin = notes.selectByRemoteId(1).executeAsOne().local_id

        fun hiddenAfterInsert(
            category: String,
            conflictOf: Long? = null,
        ): Boolean {
            notes.insertNew("t", category, "body", 5, conflictOf)
            return notes.selectByLocalId(notes.lastInsertedLocalId().executeAsOne()).executeAsOne().hidden
        }
        assertTrue(hiddenAfterInsert("a/b"))
        assertTrue(hiddenAfterInsert("a/b/c"))
        assertTrue(hiddenAfterInsert("a/b", conflictOf = origin))
        assertFalse(hiddenAfterInsert("a/bc"))
        assertFalse(hiddenAfterInsert("a"))
        assertFalse(hiddenAfterInsert(""))
    }

    @Test
    fun recomputeHiddenCoversDirtyAndNewRows() {
        server(1, category = "a/b")
        server(2, category = "a/bc")
        val dirty = notes.selectByRemoteId(1).executeAsOne().local_id
        notes.markDirty("edit", 10, dirty)
        notes.insertNew("new", "a/b/c", "body", 11, null)
        val new = notes.lastInsertedLocalId().executeAsOne()
        val bc = notes.selectByRemoteId(2).executeAsOne().local_id

        fun hidden() = listOf(dirty, new, bc).map { notes.selectByLocalId(it).executeAsOne().hidden }

        db.settingQueries.insertExclusion("a/b", "HIDDEN")
        notes.recomputeHidden()
        assertEquals(listOf(true, true, false), hidden())
        assertEquals(listOf("DIRTY", "NEW"), listOf(dirty, new).map { notes.selectByLocalId(it).executeAsOne().sync_state })

        db.settingQueries.deleteExclusion("a/b", "HIDDEN")
        notes.recomputeHidden()
        assertEquals(listOf(false, false, false), hidden())
    }

    @Test
    fun deleteByCategoryPrefixMatchesFolderAndSubfoldersOnly() {
        listOf("a", "a/b", "a/b/c", "a/bc", "ab", "_work", "xwork", "").forEachIndexed { i, c ->
            server(i + 1L, category = c)
        }
        notes.deleteByCategoryPrefix("a/b")
        val left =
            notes
                .selectListRows(favoritesOnly = 0)
                .executeAsList()
                .map { it.category }
                .toSet()
        assertEquals(setOf("a", "a/bc", "ab", "_work", "xwork", ""), left)
        notes.deleteByCategoryPrefix("_work")
        assertTrue(notes.selectListRows(favoritesOnly = 0).executeAsList().any { it.category == "xwork" })
        assertFalse(notes.selectListRows(favoritesOnly = 0).executeAsList().any { it.category == "_work" })
    }

    @Test
    fun deleteByLocalId() {
        server(1)
        notes.deleteByLocalId(notes.selectByRemoteId(1).executeAsOne().local_id)
        assertEquals(0, notes.countNotes().executeAsOne())
    }

    @Test
    fun listRowsSkipHiddenAndDeletedAndOrderFavoritesFirst() {
        server(1, modified = 100)
        server(2, modified = 300)
        server(3, modified = 50, favorite = true)
        server(4, modified = 400)
        server(5, modified = 500, category = "secret", hidden = true)
        notes.markDeleted(notes.selectByRemoteId(4).executeAsOne().local_id)
        notes.insertNew("local", "", "# local\n\nbody", 200, null)
        val rows = notes.selectListRows(favoritesOnly = 0).executeAsList()
        assertEquals(listOf("t3", "t2", "local", "t1"), rows.map { it.title })
        val local = rows[2]
        assertEquals("body", local.excerpt)
        assertEquals("NEW", local.sync_state)
    }

    @Test
    fun listRowsFavoritesOnly() {
        server(1, favorite = true, modified = 1)
        server(2)
        server(3, favorite = true, modified = 3)
        server(4, favorite = true, category = "secret", hidden = true)
        assertEquals(listOf("t3", "t1"), notes.selectListRows(favoritesOnly = 1).executeAsList().map { it.title })
    }

    @Test
    fun folderNotesUseOnlyTheExactFolder() {
        server(1, category = "a")
        server(2, category = "a/b")
        server(3, category = "a")
        server(4, category = "a", hidden = true)
        server(5, category = "a")
        notes.markDeleted(notes.selectByRemoteId(5).executeAsOne().local_id)
        assertEquals(
            setOf("t1", "t3"),
            notes
                .selectFolderNotes("a")
                .executeAsList()
                .map { it.title }
                .toSet(),
        )
        assertEquals(listOf("t2"), notes.selectFolderNotes("a/b").executeAsList().map { it.title })
        assertEquals(emptyList(), notes.selectFolderNotes("").executeAsList())
    }

    @Test
    fun countVisibleByFolderSkipsHidden() {
        server(1)
        server(2, category = "a")
        server(3, category = "a")
        server(4, category = "a/b")
        server(5, category = "secret", hidden = true)
        server(6, category = "a")
        notes.markDeleted(notes.selectByRemoteId(6).executeAsOne().local_id)
        assertEquals(
            mapOf("" to 1L, "a" to 2L, "a/b" to 1L),
            notes.countVisibleByFolder().executeAsList().associate { it.category to it.notes },
        )
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
        assertEquals(5, CittiDatabase.Schema.version)
    }

    /** The v2 schema as 1.sqm leaves it, which is what databases/2.db holds. */
    private fun v2Driver(): JdbcSqliteDriver {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "CREATE TABLE meta (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)", 0)
        CittiDatabase.Schema.migrate(driver, 1, 2)
        return driver
    }

    private fun JdbcSqliteDriver.strings(sql: String): List<String> =
        executeQuery(
            null,
            sql,
            { c ->
                val out = mutableListOf<String>()
                while (c.next().value) out += c.getString(0).orEmpty()
                QueryResult.Value(out)
            },
            0,
        ).value

    @Test
    fun migrationTwoAddsIndexExcerptAndFts() {
        val driver = v2Driver()
        driver.execute(
            null,
            "INSERT INTO note(remote_id, etag, title, category, content, modified) VALUES " +
                "(1, 'e1', 'Été à Paris', 'voyages', 'Une journée', 1), (2, 'e2', 'Courses', '', NULL, 2)",
            0,
        )
        CittiDatabase.Schema.migrate(driver, 2, CittiDatabase.Schema.version)

        val indexes = driver.strings("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'note'")
        assertTrue("note_category_title" in indexes)
        assertFalse("note_category" in indexes)
        val migrated = CittiDatabase(driver).noteQueries
        assertNull(migrated.selectByRemoteId(1).executeAsOne().excerpt)
        // The migration indexed the existing notes: by content and accent-folded title, and by title alone.
        val found = "SELECT note.remote_id FROM note_fts JOIN note ON note.local_id = note_fts.docid WHERE note_fts MATCH "
        assertEquals(listOf("1"), driver.strings("$found '\"journee*\"'"))
        assertEquals(listOf("1"), driver.strings("$found '\"ete*\"'"))
        assertEquals(listOf("2"), driver.strings("$found '\"courses*\"'"))
        // and the triggers keep it current
        migrated.updateContent("Une journée d'été", "e1b", migrated.selectByRemoteId(1).executeAsOne().local_id)
        assertEquals(listOf("1"), driver.strings("$found '\"ete*\" \"journee*\"'"))
        assertEquals(5, CittiDatabase.Schema.version)
    }

    @Test
    fun migrationThreeAddsCreatedFromAndKeepsPendingRows() {
        val driver = v2Driver()
        CittiDatabase.Schema.migrate(driver, 2, 3)
        // A NEW row from before M5: it has no created_from, so the push never drops it.
        driver.execute(null, "INSERT INTO note(title, category, content, modified, sync_state) VALUES ('Old', '', '', 1, 'NEW')", 0)
        CittiDatabase.Schema.migrate(driver, 3, CittiDatabase.Schema.version)

        val migrated = CittiDatabase(driver).noteQueries
        val old = migrated.selectDirty().executeAsOne()
        assertNull(old.created_from)
        assertEquals(0L, migrated.dropIfUntouched(old.local_id).value)
        migrated.insertNew("New", "", "", 2, null, createdFrom = "")
        val fresh = migrated.selectByLocalId(migrated.lastInsertedLocalId().executeAsOne()).executeAsOne()
        assertEquals("", fresh.created_from)
        assertEquals(1L, migrated.dropIfUntouched(fresh.local_id).value)
    }

    @Test
    fun excerptBackfillFillsOnlyMissingExcerpts() {
        val driver = v2Driver()
        driver.execute(
            null,
            "INSERT INTO note(remote_id, etag, title, category, content, modified) VALUES " +
                "(1, 'e1', 'A', '', '# A\n\nfirst', 1), (2, 'e2', 'B', '', NULL, 2), (3, 'e3', 'C', '', '', 3)",
            0,
        )
        CittiDatabase.Schema.migrate(driver, 2, CittiDatabase.Schema.version)
        val db = CittiDatabase(driver)

        assertEquals(2, ExcerptBackfill.run(db))
        assertEquals(
            "first",
            db.noteQueries
                .selectByRemoteId(1)
                .executeAsOne()
                .excerpt,
        )
        assertNull(
            db.noteQueries
                .selectByRemoteId(2)
                .executeAsOne()
                .excerpt,
        )
        assertEquals(
            "",
            db.noteQueries
                .selectByRemoteId(3)
                .executeAsOne()
                .excerpt,
        )
        assertEquals(0, ExcerptBackfill.run(db))
    }

    @Test
    fun writesThatSetContentStoreTheExcerpt() {
        server(1, title = "Plan")
        val id = notes.selectByRemoteId(1).executeAsOne().local_id
        notes.updateContent("# Plan\n\nfrom the server", "e1", id)
        assertEquals("from the server", notes.selectByLocalId(id).executeAsOne().excerpt)
        notes.markDirty("- [ ] typed here", 5, id)
        assertEquals("typed here", notes.selectByLocalId(id).executeAsOne().excerpt)
        notes.insertNew("New", "", "> quoted", 6, null)
        assertEquals("quoted", notes.selectByLocalId(notes.lastInsertedLocalId().executeAsOne()).executeAsOne().excerpt)
    }

    @Test
    fun favoriteWidgetRowsAreTheFavoritesByModifiedDescWithoutHiddenOrDeleted() {
        server(1, favorite = true, modified = 10)
        server(2, favorite = true, modified = 30)
        server(3, favorite = false, modified = 50)
        server(4, favorite = true, modified = 40, category = "secret", hidden = true)
        server(5, favorite = true, modified = 20)
        notes.markDeleted(notes.selectByRemoteId(5).executeAsOne().local_id)
        assertEquals(listOf("t2", "t1"), notes.selectFavoriteWidgetRows().executeAsList().map { it.title })
    }

    @Test
    fun noteWidgetReturnsAHiddenNoteAndNothingForADeletedOne() {
        server(1, category = "secret", hidden = true)
        server(2)
        val hidden = notes.selectByRemoteId(1).executeAsOne().local_id
        val gone = notes.selectByRemoteId(2).executeAsOne().local_id
        notes.markDeleted(gone)
        assertEquals("t1", notes.selectNoteWidget(hidden).executeAsOne().title)
        assertNull(notes.selectNoteWidget(gone).executeAsOneOrNull())
    }

    @Test
    fun theReadingPositionSurvivesSyncAndLocalWrites() {
        server(1)
        val id = notes.selectByRemoteId(1).executeAsOne().local_id
        notes.updateContent("a\nb\nc", "e1", id)
        assertNull(notes.selectViewLine(id).executeAsOne().view_line)
        notes.setViewLine(2, id)
        server(1, etag = "e2", title = "renamed", favorite = true)
        assertEquals(2L, notes.selectViewLine(id).executeAsOne().view_line)
        notes.updateContent("a\nb\nc\nd", "e2", id)
        notes.markDirty("a\nb\nc\nd\ne", 9, id)
        assertEquals(2L, notes.selectViewLine(id).executeAsOne().view_line)
        notes.markSynced(1, "e3", "renamed", "renamed", "", "", "a\nb\nc\nd\ne", true, 11, id)
        assertEquals(2L, notes.selectViewLine(id).executeAsOne().view_line)
    }

    @Test
    fun migrationFourAddsAnEmptyReadingPosition() {
        val driver = v2Driver()
        CittiDatabase.Schema.migrate(driver, 2, 3)
        driver.execute(null, "INSERT INTO note(title, category, content, modified) VALUES ('Old', '', 'x', 1)", 0)
        CittiDatabase.Schema.migrate(driver, 3, CittiDatabase.Schema.version)
        val migrated = CittiDatabase(driver).noteQueries
        val id = 1L
        assertNull(migrated.selectViewLine(id).executeAsOne().view_line)
        migrated.setViewLine(7, id)
        assertEquals(7L, migrated.selectViewLine(id).executeAsOne().view_line)
    }
}
