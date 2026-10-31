package app.citti.core.note

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.db.CittiDatabase
import app.citti.core.insertNew
import app.citti.core.note.NoteEditor.SaveResult
import app.citti.core.updateContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NoteEditorTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { CittiDatabase.Schema.create(it) }
    private val db = CittiDatabase(driver)
    private val notes = db.noteQueries
    private var pushes = 0

    // 2026-10-31 in epoch seconds.
    private val clock = { 1_793_404_800L }

    private fun editor(localId: Long) = NoteEditor(db, localId, clock) { pushes++ }

    /** A SYNCED server Note with text, etag `e1`. */
    private fun synced(
        content: String = "# Plan\n\nbody",
        title: String = "Plan",
        category: String = "work",
        readonly: Boolean = false,
    ): Long {
        notes.upsertFromServer(1, "e1", title, category, false, 100, readonly, false)
        val id = notes.selectByRemoteId(1).executeAsOne().local_id
        notes.updateContent(content, "e1", id)
        return id
    }

    private fun row(id: Long) = notes.selectByLocalId(id).executeAsOne()

    @Test
    fun saveWritesContentExcerptAndMarksDirty() {
        val id = synced()
        val doc = assertNotNull(editor(id).load())
        val result = editor(id).also { it.load() }.save("# Plan\n\nnew body", doc.baseEtag)
        assertEquals(SaveResult.Saved, result)
        val r = row(id)
        assertEquals("# Plan\n\nnew body", r.content)
        assertEquals("new body", r.excerpt)
        assertEquals("DIRTY", r.sync_state)
        assertEquals(1_793_404_800L, r.modified)
    }

    @Test
    fun loadCarriesTheFavoriteFlagForTheMenu() {
        val id = synced()
        assertEquals(false, editor(id).load()?.favorite)
        notes.setFavorite(true, id)
        assertEquals(true, editor(id).load()?.favorite)
    }

    @Test
    fun newStaysNew() {
        notes.insertNew("T", "c", "a", 1, null)
        val id = notes.lastInsertedLocalId().executeAsOne()
        val e = editor(id).also { it.load() }
        assertEquals(SaveResult.Saved, e.save("b", null))
        assertEquals("NEW", row(id).sync_state)
    }

    @Test
    fun readOnlyIsRefused() {
        val id = synced(readonly = true)
        val e = editor(id).also { it.load() }
        assertEquals(SaveResult.ReadOnly, e.save("x", "e1"))
        assertEquals("# Plan\n\nbody", row(id).content)
        assertEquals("SYNCED", row(id).sync_state)
    }

    @Test
    fun saveUnchangedTextIsANoOp() {
        val id = synced()
        val e = editor(id).also { it.load() }
        assertEquals(SaveResult.Saved, e.save("# Plan\n\nbody", "e1"))
        assertEquals("SYNCED", row(id).sync_state)
        assertEquals(0, pushes)
    }

    @Test
    fun staleBaseMakesAConflictCopyAndLeavesTheRow() {
        val id = synced()
        val e = editor(id).also { it.load() }
        // A pull brings a new server version: new etag, content cleared.
        notes.upsertFromServer(1, "e2", "Plan", "work", false, 200, false, false)
        val result = assertIs<SaveResult.Conflict>(e.save("my text", "e1"))
        val copy = row(result.copyLocalId)
        assertEquals("my text", copy.content)
        assertEquals(id, copy.conflict_of)
        assertEquals("Plan (conflict 2026-10-31)", copy.title)
        assertEquals("work", copy.category)
        // The row itself was not written.
        assertEquals("SYNCED", row(id).sync_state)
        assertNull(row(id).content)
        assertEquals(0, pushes)
        assertEquals(copy.local_id to copy.title, e.load()!!.conflictCopy)
    }

    @Test
    fun staleBaseWithDownloadedTextAlsoConflicts() {
        val id = synced()
        val e = editor(id).also { it.load() }
        notes.upsertFromServer(1, "e2", "Plan", "work", false, 200, false, false)
        notes.updateContent("server text", "e2", id)
        assertIs<SaveResult.Conflict>(e.save("my text", "e1"))
        assertEquals("server text", row(id).content)
    }

    @Test
    fun saveAfterOwnPushUsesTheRefreshedBase() {
        val id = synced()
        val e = editor(id).also { it.load() }
        assertEquals(SaveResult.Saved, e.save("one", "e1"))
        notes.markSynced(1, "e5", "Plan", "Plan", "work", "work", "one", false, 300, id)
        assertEquals(SaveResult.Saved, e.save("two", "e5"))
        assertEquals("two", row(id).content)
        assertIs<SaveResult.Conflict>(e.save("three", "e1"))
    }

    @Test
    fun deletedRowGivesAnUnlinkedCopy() {
        val id = synced()
        val e = editor(id).also { it.load() }
        notes.markDeleted(id)
        val result = assertIs<SaveResult.Gone>(e.save("kept", "e1"))
        val copy = row(result.copyLocalId)
        assertEquals("kept", copy.content)
        assertNull(copy.conflict_of)
        assertEquals("work", copy.category)
    }

    @Test
    fun removedRowGivesAnUnlinkedCopy() {
        val id = synced()
        val e = editor(id).also { it.load() }
        driver.execute(null, "DELETE FROM note WHERE local_id = ?", 1) { bindLong(0, id) }
        val result = assertIs<SaveResult.Gone>(e.save("kept", "e1"))
        assertEquals("Plan (conflict 2026-10-31)", row(result.copyLocalId).title)
        assertNull(row(result.copyLocalId).conflict_of)
    }

    @Test
    fun updateContentSkipsALocallyEditedRow() {
        val id = synced()
        val e = editor(id).also { it.load() }
        e.save("mine", "e1")
        assertEquals(0L, notes.updateContent("server", "x", "e9", id).value)
        assertEquals("mine", row(id).content)
        assertEquals("e1", row(id).etag)
    }

    @Test
    fun afterEditRunsOncePerSuccessfulSave() {
        val id = synced()
        val e = editor(id).also { it.load() }
        e.save("a", "e1")
        e.save("a", "e1")
        assertEquals(1, pushes)
        notes.markSynced(1, "e5", "Plan", "Plan", "work", "work", "a", false, 300, id)
        notes.upsertFromServer(1, "e6", "Plan", "work", false, 400, false, false)
        assertIs<SaveResult.Conflict>(e.save("b", "e5"))
        assertEquals(1, pushes)
    }

    @Test
    fun toggleTaskFlipsOnlyTheBox() {
        val text = "---\ntags: a\n---\n- [ ] one\r\n- [x] two\n"
        val id = synced(content = text)
        val e = editor(id).also { it.load() }
        val box = text.indexOf("[ ]")
        assertEquals(SaveResult.Saved, e.toggleTask(box, "[ ]", "e1"))
        val expected = text.substring(0, box + 1) + "x" + text.substring(box + 2)
        assertEquals(expected, row(id).content)
        assertEquals(expected, e.savedText)
        assertEquals("DIRTY", row(id).sync_state)
        assertEquals(1, pushes)
        assertEquals(SaveResult.Saved, e.toggleTask(expected.indexOf("[x] two"), "[x]", "e1"))
        assertEquals(expected.replace("[x] two", "[ ] two"), row(id).content)
    }

    @Test
    fun toggleTaskMismatchWritesNothing() {
        val id = synced(content = "- [ ] one")
        val e = editor(id).also { it.load() }
        assertEquals(SaveResult.Mismatch, e.toggleTask(2, "[x]", "e1"))
        assertEquals(SaveResult.Mismatch, e.toggleTask(100, "[ ]", "e1"))
        assertEquals("- [ ] one", row(id).content)
        assertEquals("SYNCED", row(id).sync_state)
        assertEquals(0, pushes)
    }

    @Test
    fun toggleTaskUsesTheEditBase() {
        val id = synced(content = "- [ ] one")
        val e = editor(id).also { it.load() }
        notes.upsertFromServer(1, "e2", "Plan", "work", false, 200, false, false)
        notes.updateContent("- [ ] one", "e2", id)
        val result = assertIs<SaveResult.Conflict>(e.toggleTask(2, "[ ]", "e1"))
        assertEquals("- [x] one", row(result.copyLocalId).content)
        assertEquals("- [ ] one", row(id).content)
    }

    @Test
    fun insertAtPutsTheBlockOnItsOwnLineOfTheStoredText() {
        val id = synced(content = "one\ntwo")
        val e = editor(id).also { it.load() }
        assertEquals(SaveResult.Saved, e.insertAt(5, "![](a.jpg)", "e1"))
        assertEquals("one\nt\n![](a.jpg)\nwo", row(id).content)
        assertEquals("one\nt\n![](a.jpg)\nwo", e.savedText)
        assertEquals("DIRTY", row(id).sync_state)
        assertEquals(1, pushes)
    }

    @Test
    fun insertAtClampsTheOffsetToTheStoredText() {
        val id = synced(content = "one")
        val e = editor(id).also { it.load() }
        assertEquals(SaveResult.Saved, e.insertAt(40, "![](a.jpg)", "e1"))
        assertEquals("one\n![](a.jpg)", row(id).content)
    }

    @Test
    fun insertAtOnAStaleBaseMakesAConflictCopy() {
        val id = synced(content = "one")
        val e = editor(id).also { it.load() }
        notes.upsertFromServer(1, "e2", "Plan", "work", false, 200, false, false)
        notes.updateContent("server", "e2", id)
        val result = assertIs<SaveResult.Conflict>(e.insertAt(3, "![](a.jpg)", "e1"))
        assertEquals("ser\n![](a.jpg)\nver", row(result.copyLocalId).content)
        assertEquals("server", row(id).content)
    }

    @Test
    fun insertAtOnAReadOnlyOrDeletedNote() {
        val ro = synced(content = "one", readonly = true)
        assertEquals(SaveResult.ReadOnly, editor(ro).also { it.load() }.insertAt(0, "![](a.jpg)", "e1"))
        assertEquals("one", row(ro).content)
        notes.upsertFromServer(2, "e1", "Other", "work", false, 100, false, false)
        val id = notes.selectByRemoteId(2).executeAsOne().local_id
        notes.updateContent("kept", "e1", id)
        val e = editor(id).also { it.load() }
        notes.markDeleted(id)
        val gone = assertIs<SaveResult.Gone>(e.insertAt(4, "![](a.jpg)", "e1"))
        assertEquals("kept\n![](a.jpg)", row(gone.copyLocalId).content)
    }
}
