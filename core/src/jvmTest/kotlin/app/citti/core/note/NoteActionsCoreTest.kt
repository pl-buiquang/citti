package app.citti.core.note

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.api.NotesApi
import app.citti.core.api.NotesApiConfig
import app.citti.core.api.createNotesHttpClient
import app.citti.core.db.CittiDatabase
import app.citti.core.fake.Endpoint
import app.citti.core.fake.FakeNotesServer
import app.citti.core.insertNew
import app.citti.core.settings.ExclusionKind
import app.citti.core.settings.ExclusionRepository
import app.citti.core.sync.ConflictCopies
import app.citti.core.sync.FolderSync
import app.citti.core.sync.PullSync
import app.citti.core.sync.PushSync
import app.citti.core.sync.SyncEngine
import app.citti.core.sync.SyncTrigger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Core tests of [NoteActions], named apart from the E2E `NoteActionsTest`. */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteActionsCoreTest {
    private val scheduler = TestCoroutineScheduler()
    private val server = FakeNotesServer().also { it.engine.config.dispatcher = StandardTestDispatcher(scheduler) }
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val db: CittiDatabase = CittiDatabase.Schema.create(driver).let { CittiDatabase(driver) }
    private val notes = db.noteQueries
    private val exclusions = ExclusionRepository(db)
    private val clock = 1_793_448_000L
    private var now = clock

    private val config = NotesApiConfig("https://host/", "alice", "s3cret")
    private val api = NotesApi(createNotesHttpClient(server.engine, config), config)
    private val engine =
        SyncEngine(
            PushSync(api, db) { now },
            PullSync(api, db),
            db,
            sleep = {},
            folderSync = FolderSync(db, api, exclusions),
        ) { now }
    private var pushes = 0

    private fun TestScope.actions(): NoteActions {
        val creator = NoteCreator(db, engine, this, exclusions, { pushes++ }, { now })
        return NoteActions(db, creator, exclusions, { pushes++ }, { now }, StandardTestDispatcher(scheduler))
    }

    private fun test(body: suspend TestScope.() -> Unit) = runTest(StandardTestDispatcher(scheduler)) { body() }

    /** Adds a Note on the server and pulls it; returns its local id. */
    private suspend fun synced(
        title: String,
        category: String = "",
        content: String = "text",
        readonly: Boolean = false,
    ): Long {
        val remote = server.addNote(title, content, category = category, readonly = readonly)
        engine.sync(SyncTrigger.Manual)
        return notes.selectByRemoteId(remote).executeAsOne().local_id
    }

    private fun newRow(
        title: String,
        category: String = "",
    ): Long {
        notes.insertNew(title, category, "", clock, null, "")
        return notes.lastInsertedLocalId().executeAsOne()
    }

    private fun row(id: Long) = notes.selectByLocalId(id).executeAsOneOrNull()

    private fun searchIds(match: String) =
        notes
            .suggestTitles(match)
            .executeAsList()
            .map { it.local_id }

    // ---- Favorite ----

    @Test
    fun favoriteMarksASyncedNoteDirtyAndKeepsANewNoteNew() =
        test {
            val actions = actions()
            val synced = synced("S")
            val fresh = newRow("N")

            assertTrue(actions.setFavorite(synced, true))
            assertTrue(actions.setFavorite(fresh, true))

            assertEquals("DIRTY", row(synced)!!.sync_state)
            assertTrue(row(synced)!!.favorite)
            assertEquals("NEW", row(fresh)!!.sync_state)
            assertTrue(row(fresh)!!.favorite)
            assertEquals(2, pushes)
        }

    @Test
    fun favoriteIsRefusedOnAReadOnlyOrDeletedNote() =
        test {
            val actions = actions()
            val ro = synced("RO", readonly = true)
            val gone = synced("Gone")
            notes.markDeleted(gone)

            assertFalse(actions.setFavorite(ro, true))
            assertFalse(actions.setFavorite(gone, true))
            assertFalse(actions.setFavorite(9_999, true))
            assertFalse(row(ro)!!.favorite)
            assertEquals("SYNCED", row(ro)!!.sync_state)
            assertEquals(0, pushes)
        }

    // ---- Rename ----

    @Test
    fun renameWritesTheTitleAndTheSearchIndexFollows() =
        test {
            val actions = actions()
            val id = synced("Old title", "journal")
            now = clock + 60

            assertEquals(RenameResult.Done, actions.rename(id, "  Fresh name  "))

            val r = row(id)!!
            assertEquals("Fresh name", r.title)
            assertEquals("journal", r.category)
            assertEquals("DIRTY", r.sync_state)
            assertEquals(clock + 60, r.modified)
            assertEquals(listOf(id), searchIds("fresh*"))
            assertEquals(emptyList(), searchIds("old*"))
            assertEquals(1, pushes)
        }

    @Test
    fun renameOntoALocalTitleIsAClash() =
        test {
            val actions = actions()
            val id = synced("A", "journal")
            synced("B", "journal")
            val requestsBefore = server.requests.size

            assertEquals(RenameResult.Clash("journal"), actions.rename(id, "B"))
            assertEquals("A", row(id)!!.title)
            assertEquals(0, pushes)
            // A local hit answers at once, but the Folder is still refreshed in the background.
            testScheduler.advanceUntilIdle()
            assertEquals(1, server.requests.drop(requestsBefore).count { it.endpoint == Endpoint.LIST })
        }

    @Test
    fun renameOntoATitleOnlyTheServerHasIsAClashAfterTheRefresh() =
        test {
            val actions = actions()
            val id = synced("A", "journal")
            server.addNote("B", "b", category = "journal")

            assertEquals(RenameResult.Clash("journal"), actions.rename(id, "B"))
            assertEquals("A", row(id)!!.title)
            assertNotNull(notes.selectExactByFolderAndTitle("journal", "B").executeAsOneOrNull())
        }

    @Test
    fun renameOfflineAppliesTheLocalCheckOnly() =
        test {
            val actions = actions()
            val id = synced("A", "journal")
            server.offline = true

            assertEquals(RenameResult.Done, actions.rename(id, "B"))
            assertEquals("B", row(id)!!.title)
        }

    @Test
    fun aCaseOnlyRenameIsARenameAndAnUnchangedTitleDoesNothing() =
        test {
            val actions = actions()
            val id = synced("note", "journal")

            assertEquals(RenameResult.Unchanged, actions.rename(id, "note"))
            assertEquals("SYNCED", row(id)!!.sync_state)
            assertEquals(0, pushes)

            assertEquals(RenameResult.Done, actions.rename(id, "Note"))
            assertEquals("Note", row(id)!!.title)
        }

    @Test
    fun renameRefusesInvalidTitles() =
        test {
            val actions = actions()
            val id = synced("A")

            for (c in listOf("/", "\\")) assertEquals(RenameResult.Invalid(NameError.Slash), actions.rename(id, "a${c}b"))
            for (c in listOf(":", "*", "?", "\"", "<", ">", "|")) {
                assertEquals(RenameResult.Invalid(NameError.UnsafeCharacter), actions.rename(id, "a${c}b"), c)
            }
            assertEquals(RenameResult.Invalid(NameError.LeadingDot), actions.rename(id, ".hidden"))
            assertEquals(RenameResult.Invalid(NameError.Blank), actions.rename(id, "   "))
            assertEquals("A", row(id)!!.title)
            assertEquals(0, pushes)
        }

    @Test
    fun renameAndMoveAreRefusedOnReadOnlyAndGoneNotes() =
        test {
            val actions = actions()
            val ro = synced("RO", readonly = true)
            val gone = synced("Gone")
            notes.markDeleted(gone)

            assertEquals(RenameResult.ReadOnly, actions.rename(ro, "X"))
            assertEquals(RenameResult.ReadOnly, actions.move(ro, "journal"))
            assertEquals(RenameResult.Gone, actions.rename(gone, "X"))
            assertEquals(RenameResult.Gone, actions.move(9_999, "journal"))
        }

    @Test
    fun renameOfANewNoteKeepsItNew() =
        test {
            val actions = actions()
            val id = newRow("Draft")
            server.offline = true

            assertEquals(RenameResult.Done, actions.rename(id, "Plan"))
            assertEquals("NEW", row(id)!!.sync_state)
            assertEquals("Plan", row(id)!!.title)
        }

    // ---- Move ----

    @Test
    fun moveRecomputesHiddenIntoAndOutOfAHiddenFolder() =
        test {
            exclusions.add(ExclusionKind.HIDDEN, "secret")
            val actions = actions()
            val id = synced("T", "journal")

            assertEquals(RenameResult.Done, actions.move(id, "secret/sub"))
            assertEquals("secret/sub", row(id)!!.category)
            assertTrue(row(id)!!.hidden)
            assertEquals(listOf(id), searchIds("category:secret"))

            assertEquals(RenameResult.Done, actions.move(id, "/journal//2026/"))
            assertEquals("journal/2026", row(id)!!.category)
            assertFalse(row(id)!!.hidden)
        }

    @Test
    fun moveIntoAFolderThatHasTheTitleIsAClash() =
        test {
            val actions = actions()
            val id = synced("T", "journal")
            synced("T", "projects")
            // Exact match: `t` in another Folder is no clash.
            synced("t", "archive")

            assertEquals(RenameResult.Clash("projects"), actions.move(id, "projects"))
            assertEquals("journal", row(id)!!.category)
            assertEquals(RenameResult.Done, actions.move(id, "archive"))
        }

    @Test
    fun moveRefusesUnsyncedAndInvalidFolders() =
        test {
            exclusions.add(ExclusionKind.UNSYNCED, "archive")
            val actions = actions()
            val id = synced("T", "journal")

            assertEquals(RenameResult.Invalid(NameError.Unsynced), actions.move(id, "archive/2025"))
            assertEquals(RenameResult.Invalid(NameError.DotSegment), actions.move(id, "a/../b"))
            assertEquals(RenameResult.Invalid(NameError.DotSegment), actions.move(id, "./a"))
            assertEquals(RenameResult.Invalid(NameError.UnsafeCharacter), actions.move(id, "a/b:c"))
            assertEquals(RenameResult.Unchanged, actions.move(id, "/journal/"))
            assertEquals("journal", row(id)!!.category)
            assertEquals(0, pushes)
        }

    @Test
    fun normaliseFolderCases() {
        assertEquals("a/b", NameRules.normaliseFolder("/a//b/", emptyList()).getOrThrow())
        assertEquals("", NameRules.normaliseFolder("  ", emptyList()).getOrThrow())
        for (bad in listOf("..", "a/..", ".", "a/./b")) {
            val error = (NameRules.normaliseFolder(bad, emptyList()).exceptionOrNull() as InvalidNameException).error
            assertEquals(NameError.DotSegment, error, bad)
        }
    }

    // ---- Delete ----

    @Test
    fun deleteRemovesANewNoteAndMarksASyncedNoteDeleted() =
        test {
            val actions = actions()
            // After the sync: a sync would push the NEW row.
            val synced = synced("S")
            val fresh = newRow("N")

            assertTrue(actions.delete(fresh))
            assertNull(row(fresh))
            assertTrue(actions.delete(synced))
            assertEquals("DELETED", row(synced)!!.sync_state)
            assertEquals(2, pushes)
            assertFalse(actions.delete(synced))
        }

    @Test
    fun deleteIsRefusedOnAReadOnlyNote() =
        test {
            val actions = actions()
            val ro = synced("RO", readonly = true)

            assertFalse(actions.delete(ro))
            assertEquals("SYNCED", row(ro)!!.sync_state)
            assertEquals(0, pushes)
        }

    // ---- Push ----

    @Test
    fun theServerSeesRenameMoveFavoriteAndDeleteAfterThePush() =
        test {
            val actions = actions()
            val a = synced("A", "journal")
            val b = synced("B", "journal")
            val remoteA = row(a)!!.remote_id!!
            val remoteB = row(b)!!.remote_id!!

            actions.rename(a, "A2")
            actions.move(a, "projects")
            actions.setFavorite(a, true)
            actions.delete(b)
            engine.push()

            val serverA = server.note(remoteA)!!
            assertEquals("A2", serverA.title)
            assertEquals("projects", serverA.category)
            assertTrue(serverA.favorite)
            assertNull(server.note(remoteB))
            assertEquals("SYNCED", row(a)!!.sync_state)
        }

    // ---- Note info ----

    @Test
    fun infoShowsTheMetadataAndTheCounts() =
        test {
            exclusions.add(ExclusionKind.HIDDEN, "secret")
            val actions = actions()
            val id = synced("T", "secret/sub", content = "Hello wörld 42\nline two")
            actions.setFavorite(id, true)
            val copy = ConflictCopies(db) { now }.create("T", "secret/sub", "local text", id)

            val info = assertNotNull(actions.info(id))
            assertEquals("T", info.title)
            assertEquals("secret/sub", info.folder)
            assertEquals(5, info.words)
            assertEquals(2, info.lines)
            assertEquals(23, info.chars)
            assertEquals(24, info.bytes)
            assertEquals(InfoState.Pending, info.state)
            assertTrue(info.favorite)
            assertFalse(info.readonly)
            assertEquals("secret", info.hiddenUnder)
            assertEquals(copy, info.conflictCopy?.first)

            assertEquals(InfoState.ConflictCopy, actions.info(copy)!!.state)
            assertEquals(InfoState.Synced, actions.info(synced("U"))!!.state)
            assertNull(actions.info(9_999))
        }

    @Test
    fun textCountsEdgeCases() {
        assertEquals(TextCounts(0, 0, 0, 0), TextCounts.of(""))
        // CRLF: one line break per \n; \r is a character.
        assertEquals(TextCounts(8, 2, 8, 3), TextCounts.of("ab\r\ncd\r\n"))
        // Emoji are one code point each, and no word.
        assertEquals(TextCounts(11, 1, 5, 1), TextCounts.of("hi \uD83D\uDE00\uD83D\uDE00"))
        // No final newline.
        assertEquals(TextCounts(9, 3, 9, 2), TextCounts.of("one\ntwo 3"))
        // Letters of other scripts, and a combining accent inside a word.
        assertEquals(3, TextCounts.of("Привет 世界 cafe\u0301").words)
    }
}
