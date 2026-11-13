package app.citti.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.api.ApiError
import app.citti.core.api.NotesApi
import app.citti.core.api.NotesApiConfig
import app.citti.core.api.createNotesHttpClient
import app.citti.core.db.CittiDatabase
import app.citti.core.fake.Endpoint
import app.citti.core.fake.FakeFailure
import app.citti.core.fake.FakeNotesServer
import app.citti.core.sync.Dropped
import app.citti.core.sync.PendingChanges
import app.citti.core.sync.PushFailure
import app.citti.core.sync.PushResult
import app.citti.core.sync.PushSync
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushSyncTest {
    private val server = FakeNotesServer()
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { CittiDatabase.Schema.create(it) }
    private val db = CittiDatabase(driver)
    private val notes = db.noteQueries
    private val config = NotesApiConfig("https://host", "alice", "pw")
    private val api = NotesApi(createNotesHttpClient(server.engine, config), config)

    // 2026-10-31T12:00:00Z
    private val clock = 1_793_448_000L
    private val push = PushSync(api, db) { clock }

    /** A Note already synced with a fresh server note. */
    private fun synced(
        title: String = "A",
        content: String = "body",
        category: String = "",
        readonly: Boolean = false,
    ): Pair<Long, Long> {
        val remote = server.addNote(title, content, category, readonly = readonly)
        val n = server.note(remote)!!
        notes.upsertFromServer(remote, n.etag, title, category, false, n.modified, readonly, false)
        val local = notes.selectByRemoteId(remote).executeAsOne().local_id
        notes.updateContent(content, n.etag, local)
        return local to remote
    }

    private fun newLocal(
        title: String,
        content: String,
        category: String = "",
        createdFrom: String? = null,
    ): Long {
        notes.insertNew(title, category, content, 1, null, createdFrom)
        return notes.lastInsertedLocalId().executeAsOne()
    }

    private suspend fun done() = assertIs<PushResult.Done>(push.push())

    @Test
    fun dirtyNoteIsPushedWithIfMatchAndEtagUpdated() =
        runTest {
            val (local, remote) = synced()
            val oldEtag = notes.selectByLocalId(local).executeAsOne().etag
            notes.markDirty("edited", 5, local)
            val result = done()
            assertEquals(1, result.updated)
            assertEquals("edited", server.note(remote)!!.content)
            val row = notes.selectByLocalId(local).executeAsOne()
            assertEquals("SYNCED", row.sync_state)
            assertEquals(server.note(remote)!!.etag, row.etag)
            assertTrue(row.etag != oldEtag)
            val put = server.requests.last { it.endpoint == Endpoint.UPDATE }
            assertEquals("\"$oldEtag\"", put.header("If-Match"))
            assertEquals(0, PendingChanges(db).count())
        }

    @Test
    fun newNoteIsCreated() =
        runTest {
            val local = newLocal("Fresh", "text", "a/b")
            assertEquals(1, done().created)
            val row = notes.selectByLocalId(local).executeAsOne()
            assertEquals("SYNCED", row.sync_state)
            val remote = server.note(assertNotNull(row.remote_id))!!
            assertEquals("Fresh", remote.title)
            assertEquals("a/b", remote.category)
            assertEquals("text", remote.content)
            assertEquals(remote.etag, row.etag)
        }

    @Test
    fun deletePushedAlsoWhenAlreadyGone() =
        runTest {
            val (l1, r1) = synced("one")
            val (l2, r2) = synced("two")
            server.deleteOnServer(r2)
            notes.markDeleted(l1)
            notes.markDeleted(l2)
            assertEquals(2, done().deleted)
            assertNull(server.note(r1))
            assertEquals(0, notes.countNotes().executeAsOne())
            assertEquals(0, PendingChanges(db).count())
        }

    @Test
    fun deleteWithMatchingEtagSendsIfMatch() =
        runTest {
            val (local, remote) = synced()
            val etag = notes.selectByLocalId(local).executeAsOne().etag
            notes.markDeleted(local)
            assertEquals(1, done().deleted)
            assertNull(server.note(remote))
            assertEquals("\"$etag\"", server.requests.single { it.endpoint == Endpoint.DELETE }.header("If-Match"))
        }

    @Test
    fun deleteOfNoteEditedOnServerIsUndone() =
        runTest {
            val (local, remote) = synced("Plan", "base")
            notes.markDeleted(local)
            server.editOnServer(remote, content = "server text")

            val result = done()
            assertEquals(0, result.deleted)
            assertEquals(1, result.deleteUndone)
            assertEquals(0, server.count(Endpoint.DELETE))
            assertEquals("server text", server.note(remote)!!.content)
            val row = notes.selectByLocalId(local).executeAsOne()
            assertEquals("SYNCED", row.sync_state)
            assertEquals("server text", row.content)
            assertEquals(server.note(remote)!!.etag, row.etag)
            assertEquals(0, PendingChanges(db).count())
        }

    @Test
    fun deleteRefusedByIfMatchIsUndone() =
        runTest {
            push.verifyBeforeDelete = false
            val (local, remote) = synced("Plan", "base")
            notes.markDeleted(local)
            server.editOnServer(remote, content = "server text")

            val result = done()
            assertEquals(0, result.deleted)
            assertEquals(1, result.deleteUndone)
            // no pre-check GET, only the one fetching the server version after the 412
            assertEquals(1, server.count(Endpoint.GET_NOTE))
            assertEquals(412, server.requests.single { it.endpoint == Endpoint.DELETE }.status)
            assertEquals("server text", server.note(remote)!!.content)
            val row = notes.selectByLocalId(local).executeAsOne()
            assertEquals("SYNCED", row.sync_state)
            assertEquals("server text", row.content)
        }

    /** Runs [edit] once, while the first request to [endpoint] is in flight. */
    private fun duringRequest(
        endpoint: Endpoint,
        edit: () -> Unit,
    ) {
        server.beforeDispatch = {
            if (it == endpoint) {
                server.beforeDispatch = null
                edit()
            }
        }
    }

    /** No query renames a Note yet (M3), so the test writes the title directly. */
    private fun rename(
        local: Long,
        title: String,
    ) = driver.execute(null, "UPDATE note SET title = ? WHERE local_id = ?", 2) {
        bindString(0, title)
        bindLong(1, local)
    }

    @Test
    fun newNoteWithTakenTitleFollowsServerRename() =
        runTest {
            val local = newLocal("Plan", "text", "work")
            // Another client creates the title after the folder refresh: the server renames ours.
            duringRequest(Endpoint.CREATE) { server.addNote("Plan", "older", "work") }
            assertEquals(1, done().created)
            val row = notes.selectByLocalId(local).executeAsOne()
            val remote = server.note(assertNotNull(row.remote_id))!!
            assertEquals("Plan (2)", remote.title)
            assertEquals("Plan (2)", row.title)
            assertEquals(remote.category, row.category)
            assertEquals("SYNCED", row.sync_state)
            assertEquals(remote.modified, row.modified)
        }

    @Test
    fun editDuringCreateIsPushedNextRun() =
        runTest {
            val local = newLocal("Fresh", "first")
            duringRequest(Endpoint.CREATE) { notes.markDirty("typed meanwhile", 6, local) }
            assertEquals(1, done().created)

            val row = notes.selectByLocalId(local).executeAsOne()
            val remote = assertNotNull(row.remote_id)
            assertEquals("DIRTY", row.sync_state)
            assertEquals("typed meanwhile", row.content)
            assertEquals(server.note(remote)!!.etag, row.etag)
            assertEquals("first", server.note(remote)!!.content)

            assertEquals(1, done().updated)
            assertEquals("typed meanwhile", server.note(remote)!!.content)
            assertEquals("\"${row.etag}\"", server.requests.last { it.endpoint == Endpoint.UPDATE }.header("If-Match"))
            assertEquals("SYNCED", notes.selectByLocalId(local).executeAsOne().sync_state)
        }

    @Test
    fun editDuringUpdateIsPushedNextRun() =
        runTest {
            val (local, remote) = synced()
            notes.markDirty("first", 5, local)
            duringRequest(Endpoint.UPDATE) { notes.markDirty("second", 6, local) }
            assertEquals(1, done().updated)

            val row = notes.selectByLocalId(local).executeAsOne()
            assertEquals("DIRTY", row.sync_state)
            assertEquals("second", row.content)
            assertEquals(6, row.modified)
            assertEquals(server.note(remote)!!.etag, row.etag)

            assertEquals(1, done().updated)
            assertEquals("second", server.note(remote)!!.content)
            assertEquals("SYNCED", notes.selectByLocalId(local).executeAsOne().sync_state)
        }

    /** No query toggles a Favorite yet (M5 NoteActions), so the test writes it directly, like the menu will. */
    private fun toggleFavorite(local: Long) =
        driver.execute(
            null,
            "UPDATE note SET favorite = NOT favorite, sync_state = CASE WHEN sync_state = 'NEW' THEN 'NEW' ELSE 'DIRTY' END " +
                "WHERE local_id = ?",
            1,
        ) { bindLong(0, local) }

    @Test
    fun favoriteToggledDuringUpdateIsPushedNextRun() =
        runTest {
            val (local, remote) = synced()
            notes.markDirty("edited", 5, local)
            duringRequest(Endpoint.UPDATE) { toggleFavorite(local) }
            assertEquals(1, done().updated)

            val row = notes.selectByLocalId(local).executeAsOne()
            assertEquals("DIRTY", row.sync_state)
            assertTrue(row.favorite)
            assertEquals(false, server.note(remote)!!.favorite)
            assertEquals(server.note(remote)!!.etag, row.etag)

            assertEquals(1, done().updated)
            assertTrue(server.note(remote)!!.favorite)
            assertEquals("SYNCED", notes.selectByLocalId(local).executeAsOne().sync_state)
        }

    @Test
    fun favoriteToggledDuringCreateIsPushedNextRun() =
        runTest {
            val local = newLocal("Fresh", "first")
            duringRequest(Endpoint.CREATE) { toggleFavorite(local) }
            assertEquals(1, done().created)

            val row = notes.selectByLocalId(local).executeAsOne()
            val remote = assertNotNull(row.remote_id)
            assertEquals("DIRTY", row.sync_state)
            assertEquals(false, server.note(remote)!!.favorite)

            assertEquals(1, done().updated)
            assertTrue(server.note(remote)!!.favorite)
            assertEquals("SYNCED", notes.selectByLocalId(local).executeAsOne().sync_state)
        }

    @Test
    fun deleteDuringCreateIsPushedNextRun() =
        runTest {
            val local = newLocal("Fresh", "text")
            duringRequest(Endpoint.CREATE) { notes.markDeleted(local) }
            done()

            val row = notes.selectByLocalId(local).executeAsOne()
            val remote = assertNotNull(row.remote_id)
            assertEquals("DELETED", row.sync_state)
            assertNotNull(server.note(remote))

            assertEquals(1, done().deleted)
            assertNull(server.note(remote))
            assertNull(notes.selectByLocalId(local).executeAsOneOrNull())
        }

    @Test
    fun titleEditedDuringCreateIsKeptOverServerRename() =
        runTest {
            val local = newLocal("Plan", "text")
            duringRequest(Endpoint.CREATE) {
                server.addNote("Plan", "older")
                rename(local, "Plan B")
            }
            done()

            val row = notes.selectByLocalId(local).executeAsOne()
            assertEquals("Plan (2)", server.note(assertNotNull(row.remote_id))!!.title)
            assertEquals("Plan B", row.title)
            assertEquals("DIRTY", row.sync_state)
        }

    @Test
    fun textTypedDuringConflictFetchGoesToTheCopy() =
        runTest {
            val (local, remote) = synced("Plan", "base")
            notes.markDirty("local text", 5, local)
            server.editOnServer(remote, content = "server text")
            duringRequest(Endpoint.GET_NOTE) { notes.markDirty("local text, more", 6, local) }

            val copy = notes.selectByLocalId(done().conflicts.single()).executeAsOne()
            assertEquals("local text, more", copy.content)
            assertEquals("server text", notes.selectByLocalId(local).executeAsOne().content)
        }

    @Test
    fun deleteDuringConflictFetchTakesServerVersionWithoutCopy() =
        runTest {
            val (local, remote) = synced("Plan", "base")
            notes.markDirty("local text", 5, local)
            server.editOnServer(remote, content = "server text")
            duringRequest(Endpoint.GET_NOTE) { notes.markDeleted(local) }

            assertTrue(done().conflicts.isEmpty())
            val row = notes.selectByLocalId(local).executeAsOne()
            assertEquals("SYNCED", row.sync_state)
            assertEquals("server text", row.content)
            assertEquals(1, notes.countNotes().executeAsOne())
        }

    @Test
    fun offlineEditIsPushedOnReconnect() =
        runTest {
            val (local, remote) = synced()
            notes.markDirty("offline", 5, local)
            server.failNext(FakeFailure.NETWORK_ERROR)
            val first = push.push()
            assertIs<ApiError.Network>(assertIs<PushResult.Failed>(first).error)
            assertEquals(listOf(local), PendingChanges(db).list().map { it.local_id })
            assertEquals("DIRTY", notes.selectByLocalId(local).executeAsOne().sync_state)
            assertEquals(1, done().updated)
            assertEquals("offline", server.note(remote)!!.content)
        }

    @Test
    fun authFailureKeepsQueue() =
        runTest {
            val (local, _) = synced()
            notes.markDirty("x", 5, local)
            newLocal("N", "n")
            server.failNext(FakeFailure.UNAUTHORIZED)
            val result = push.push()
            assertIs<ApiError.Auth>(assertIs<PushResult.Failed>(result).error)
            assertEquals(2, PendingChanges(db).count())
        }

    @Test
    fun concurrentServerEditGivesConflictCopyAndLosesNothing() =
        runTest {
            val (local, remote) = synced("Plan", "base", "work")
            notes.markDirty("local text", 5, local)
            server.editOnServer(remote, content = "server text")

            val result = done()
            assertEquals(0, result.updated)
            val copyId = result.conflicts.single()

            val origin = notes.selectByLocalId(local).executeAsOne()
            assertEquals("server text", origin.content)
            assertEquals("SYNCED", origin.sync_state)
            assertEquals(server.note(remote)!!.etag, origin.etag)

            val copy = notes.selectByLocalId(copyId).executeAsOne()
            assertEquals("Plan (conflict 2026-10-31)", copy.title)
            assertEquals("work", copy.category)
            assertEquals("local text", copy.content)
            assertEquals(local, copy.conflict_of)
            // pushed in the same run
            assertEquals("SYNCED", copy.sync_state)
            val onServer = server.note(assertNotNull(copy.remote_id))!!
            assertEquals("local text", onServer.content)
            assertEquals("Plan (conflict 2026-10-31)", onServer.title)
            assertEquals("server text", server.note(remote)!!.content)
            assertEquals(0, PendingChanges(db).count())
        }

    @Test
    fun conflictWithAnUnreadableServerNoteKeepsTheLocalEditQueued() =
        runTest {
            val (local, remote) = synced("Plan", "base")
            notes.markDirty("local text", 5, local)
            server.editOnServer(remote, content = "server text")
            server.setUnreadable(remote)

            val result = done()

            // No Conflict copy, and the server's error message does not replace the local text.
            assertTrue(result.conflicts.isEmpty())
            assertEquals(listOf(PushFailure(local, ApiError.Unreadable)), result.failures)
            val row = notes.selectByLocalId(local).executeAsOne()
            assertEquals("local text", row.content)
            assertEquals("DIRTY", row.sync_state)

            server.setUnreadable(remote, false)
            val retried = done()
            assertEquals("server text", notes.selectByLocalId(local).executeAsOne().content)
            assertEquals("local text", notes.selectByLocalId(retried.conflicts.single()).executeAsOne().content)
        }

    @Test
    fun serverDeletedNoteKeepsLocalTextAsNew() =
        runTest {
            val (local, remote) = synced("Gone", "base")
            notes.markDirty("mine", 5, local)
            server.editOnServer(remote, content = "x")
            server.deleteOnServer(remote)
            val result = done()
            assertEquals(1, result.created)
            assertTrue(result.conflicts.isEmpty())
            val row = notes.selectByLocalId(local).executeAsOne()
            val newRemote = assertNotNull(row.remote_id)
            assertTrue(newRemote != remote)
            assertEquals("mine", server.note(newRemote)!!.content)
        }

    @Test
    fun conflictCopyExcerptSkipsTheOriginalTitleHeading() =
        runTest {
            val (local, remote) = synced("Plan", "base")
            notes.markDirty("# Plan\n\nlocal line", 5, local)
            server.editOnServer(remote, content = "server")
            val copy = notes.selectByLocalId(done().conflicts.single()).executeAsOne()
            assertEquals("local line", copy.excerpt)
        }

    @Test
    fun duplicateConflictTitleGetsNumericSuffix() =
        runTest {
            val (local, remote) = synced("Plan", "base")
            newLocal("Plan (conflict 2026-10-31)", "earlier")
            notes.markDirty("local", 5, local)
            server.editOnServer(remote, content = "server")
            val copy = notes.selectByLocalId(done().conflicts.single()).executeAsOne()
            assertEquals("Plan (conflict 2026-10-31) 2", copy.title)
        }

    @Test
    fun readOnlyNotesAreSkipped() =
        runTest {
            val (local, remote) = synced("RO", "base")
            notes.markDirty("edit", 5, local)
            driver.execute(null, "UPDATE note SET readonly = 1 WHERE local_id = ?", 1) { bindLong(0, local) }
            val result = done()
            assertEquals(1, result.skippedReadOnly)
            assertEquals("base", server.note(remote)!!.content)
            assertEquals("DIRTY", notes.selectByLocalId(local).executeAsOne().sync_state)
            assertEquals(0, server.count(Endpoint.UPDATE))
        }
    // ---- push-time check (ADR 0006) ----

    private fun serverTitles(category: String) =
        server.notes
            .filter { it.category == category }
            .map { it.title }
            .sorted()

    private fun localOf(remote: Long) = notes.selectByRemoteId(remote).executeAsOne().local_id

    @Test
    fun untouchedNewNoteWhoseTitleIsOnTheServerIsDropped() =
        runTest {
            val remote = server.addNote("Dup", "from the web app", "scratch")
            val local = newLocal("Dup", "", "scratch", createdFrom = "")

            val result = done()
            assertEquals(0, server.count(Endpoint.CREATE))
            assertNull(notes.selectByLocalId(local).executeAsOneOrNull())
            assertEquals(listOf(Dropped(local, localOf(remote), "Dup", "scratch")), result.dropped)
            assertEquals(listOf("Dup"), serverTitles("scratch"))
            assertEquals(0, PendingChanges(db).count())
        }

    @Test
    fun editedNewNoteWhoseTitleIsOnTheServerBecomesAConflictCopy() =
        runTest {
            val remote = server.addNote("Dup", "from the web app", "scratch")
            val local = newLocal("Dup", "typed text", "scratch", createdFrom = "")

            val result = done()
            assertTrue(result.dropped.isEmpty())
            assertEquals(listOf(local), result.conflicts)
            val row = notes.selectByLocalId(local).executeAsOne()
            assertEquals("Dup (conflict 2026-10-31)", row.title)
            assertEquals(localOf(remote), row.conflict_of)
            assertNull(row.created_from)
            assertEquals("SYNCED", row.sync_state)
            val create = server.requests.single { it.endpoint == Endpoint.CREATE }
            assertTrue("Dup (conflict 2026-10-31)" in create.body)
            assertEquals(listOf("Dup", "Dup (conflict 2026-10-31)"), serverTitles("scratch"))
        }

    @Test
    fun aFolderThatCannotBeRefreshedKeepsItsNewNotes() =
        runTest {
            val blocked = newLocal("A", "x", "a", createdFrom = "")
            val other = newLocal("B", "y", "b", createdFrom = "")
            val (synced, remote) = synced("S", "base", "c")
            notes.markDirty("edited", 5, synced)
            server.failNext(FakeFailure.NETWORK_ERROR, endpoint = Endpoint.LIST)

            val result = done()
            assertEquals(1, result.deferredNew)
            assertEquals(1, result.created)
            assertEquals(1, result.updated)
            val row = notes.selectByLocalId(blocked).executeAsOne()
            assertEquals("NEW", row.sync_state)
            assertNull(row.remote_id)
            assertEquals("SYNCED", notes.selectByLocalId(other).executeAsOne().sync_state)
            assertEquals("edited", server.note(remote)!!.content)
            assertEquals(emptyList(), serverTitles("a"))

            // The next run refreshes the folder and pushes it.
            val next = done()
            assertEquals(0, next.deferredNew)
            assertEquals(1, next.created)
            assertEquals(listOf("A"), serverTitles("a"))
        }

    @Test
    fun newNotesOfOneFolderCostOneFolderListPerPush() =
        runTest {
            newLocal("One", "1", "f", createdFrom = "")
            newLocal("Two", "2", "f", createdFrom = "")
            newLocal("Root", "r", createdFrom = "")

            assertEquals(3, done().created)
            val lists = server.requests.filter { it.endpoint == Endpoint.LIST }
            assertEquals(listOf("f", ""), lists.map { it.query["category"] })
            assertTrue(lists.all { it.query["exclude"] == "content" })
        }

    @Test
    fun pushWithoutNewNotesListsNothing() =
        runTest {
            val (local, _) = synced()
            notes.markDirty("edited", 5, local)
            assertEquals(1, done().updated)
            assertEquals(0, server.count(Endpoint.LIST))
        }

    @Test
    fun conflictCopyFromAConflictGoesThroughTheCheck() =
        runTest {
            val (local, remote) = synced("Plan", "base", "work")
            notes.markDirty("local text", 5, local)
            server.editOnServer(remote, content = "server text")
            // Another client already made a copy with the same title: ours must not become "(2)" on the server.
            server.addNote("Plan (conflict 2026-10-31)", "theirs", "work")

            val result = done()
            assertEquals(1, server.count(Endpoint.LIST))
            assertEquals("work", server.requests.single { it.endpoint == Endpoint.LIST }.query["category"])
            val copy = notes.selectByLocalId(result.conflicts.first()).executeAsOne()
            assertEquals("SYNCED", copy.sync_state)
            assertEquals("local text", server.note(assertNotNull(copy.remote_id))!!.content)
            assertTrue(serverTitles("work").none { it.endsWith("(2)") }, serverTitles("work").toString())
        }

    @Test
    fun createdFromIsClearedOnceSynced() =
        runTest {
            val local = newLocal("Fresh", "text", createdFrom = "text")
            assertEquals(1, done().created)
            val row = notes.selectByLocalId(local).executeAsOne()
            assertEquals("SYNCED", row.sync_state)
            assertNull(row.created_from)
        }

    @Test
    fun newNoteFromBeforeM5IsNeverDropped() =
        runTest {
            server.addNote("Dup", "server", "scratch")
            // created_from NULL, as after the migration: even an empty text is not "untouched".
            val local = newLocal("Dup", "", "scratch", createdFrom = null)

            val result = done()
            assertTrue(result.dropped.isEmpty())
            assertEquals("Dup (conflict 2026-10-31)", notes.selectByLocalId(local).executeAsOne().title)
            assertEquals(listOf("Dup", "Dup (conflict 2026-10-31)"), serverTitles("scratch"))
        }

    @Test
    fun editDuringTheFolderRefreshMakesAConflictCopyNotADrop() =
        runTest {
            server.addNote("Dup", "server", "scratch")
            val local = newLocal("Dup", "", "scratch", createdFrom = "")
            duringRequest(Endpoint.LIST) { notes.markDirty("typed meanwhile", 6, local) }

            val result = done()
            assertTrue(result.dropped.isEmpty())
            val row = notes.selectByLocalId(local).executeAsOne()
            assertEquals("Dup (conflict 2026-10-31)", row.title)
            assertEquals("typed meanwhile", server.note(assertNotNull(row.remote_id))!!.content)
            assertEquals(listOf("Dup", "Dup (conflict 2026-10-31)"), serverTitles("scratch"))
        }

    @Test
    fun titleMatchIsCaseSensitiveLikeTheServer() =
        runTest {
            server.addNote("X CASE", "server", "scratch")
            val local = newLocal("X case", "", "scratch", createdFrom = "")
            assertEquals(1, done().created)
            assertEquals("X case", notes.selectByLocalId(local).executeAsOne().title)
        }

    @Test
    fun authFailureOnTheFolderRefreshStopsTheRun() =
        runTest {
            newLocal("N", "n", createdFrom = "")
            server.failNext(FakeFailure.UNAUTHORIZED, endpoint = Endpoint.LIST)
            assertIs<ApiError.Auth>(assertIs<PushResult.Failed>(push.push()).error)
            assertEquals(0, server.count(Endpoint.CREATE))
        }
}
