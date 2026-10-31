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
import app.citti.core.sync.PendingChanges
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
    ): Long {
        notes.insertNew(title, category, content, 1, null)
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
            server.addNote("Plan", "older", "work")
            val local = newLocal("Plan", "text", "work")
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
            server.addNote("Plan", "older")
            val local = newLocal("Plan", "text")
            duringRequest(Endpoint.CREATE) { rename(local, "Plan B") }
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
}
