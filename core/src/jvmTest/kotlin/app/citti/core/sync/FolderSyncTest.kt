package app.citti.core.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.api.ApiError
import app.citti.core.api.NotesApi
import app.citti.core.api.NotesApiConfig
import app.citti.core.api.createNotesHttpClient
import app.citti.core.db.CittiDatabase
import app.citti.core.fake.Endpoint
import app.citti.core.fake.FakeNotesServer
import app.citti.core.insertNew
import app.citti.core.markDirty
import app.citti.core.settings.ExclusionKind
import app.citti.core.settings.ExclusionRepository
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FolderSyncTest {
    private val server = FakeNotesServer()
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val db: CittiDatabase = CittiDatabase.Schema.create(driver).let { CittiDatabase(driver) }
    private val notes = db.noteQueries
    private val exclusions = ExclusionRepository(db)
    private val clock = 1_793_448_000L

    /** Completes when the test lets HTTP requests through. */
    private var gate: CompletableDeferred<Unit>? = null

    private val config = NotesApiConfig("https://host/", "alice", "s3cret")
    private val api =
        NotesApi(
            createNotesHttpClient(server.engine, config).also { client ->
                client.plugin(HttpSend).intercept { request ->
                    gate?.await()
                    execute(request)
                }
            },
            config,
        )
    private val folderSync = FolderSync(db, api, exclusions)
    private val engine =
        SyncEngine(PushSync(api, db) { clock }, PullSync(api, db), db, sleep = {}, folderSync = folderSync) { clock }

    private fun storedEtag(folder: String) =
        db.syncStateQueries
            .select(FolderSync.keyOf(folder))
            .executeAsOneOrNull()
            ?.etag

    private fun local(remoteId: Long) = notes.selectByRemoteId(remoteId).executeAsOneOrNull()

    /** Every column of every row, to check that a refresh wrote nothing. */
    private fun dump() = notes.selectSyncIndex().executeAsList().map { notes.selectByLocalId(it.local_id).executeAsOne() }

    private suspend fun fullSync() = assertIs<SyncOutcome.Synced>(engine.sync(SyncTrigger.Manual))

    @Test
    fun firstRefreshStoresTheFolderEtagAndTheSecondIsOne304() =
        runTest {
            val a = server.addNote("A", "a", category = "journal")
            val b = server.addNote("B", "b", category = "journal")
            server.addNote("Elsewhere", "x", category = "projects")

            val first = assertIs<FolderRefresh.Refreshed>(folderSync.run("journal"))
            assertEquals(FolderRefresh.Refreshed(upserted = 2, deleted = 0), first)
            assertNotNull(storedEtag("journal"))
            assertNotNull(local(a))
            assertNotNull(local(b))
            assertEquals(2L, notes.countNotes().executeAsOne())
            // Content is not downloaded.
            assertNull(local(a)!!.content)
            val list = server.requests.single()
            assertEquals("journal", list.query["category"])
            assertEquals("content", list.query["exclude"])

            val before = dump()
            server.clearRequests()
            assertEquals(FolderRefresh.NotModified, folderSync.run("journal"))
            assertEquals(1, server.requests.size)
            assertEquals(304, server.requests.single().status)
            assertEquals(before, dump())
        }

    @Test
    fun theVaultRootIsItsOwnFolder() =
        runTest {
            val root = server.addNote("Root", "r")
            server.addNote("A", "a", category = "journal")

            assertEquals(FolderRefresh.Refreshed(upserted = 1, deleted = 0), folderSync.run(""))
            assertEquals("", server.requests.single().query["category"])
            assertNotNull(storedEtag(""))
            assertEquals(listOf(root), notes.selectSyncIndex().executeAsList().map { it.remote_id })
        }

    @Test
    fun deletesOnlySyncedRowsOfThatExactFolder() =
        runTest {
            val gone = server.addNote("Gone", "g", category = "journal")
            val kept = server.addNote("Kept", "k", category = "journal")
            val sub = server.addNote("Sub", "s", category = "journal/2026")
            val other = server.addNote("Other", "o", category = "projects")
            val root = server.addNote("Root", "r")
            fullSync()

            // All but one vanish from the server; only the one in the refreshed Folder may go locally.
            listOf(gone, sub, other, root).forEach(server::deleteOnServer)
            assertEquals(FolderRefresh.Refreshed(upserted = 1, deleted = 1), folderSync.run("journal"))

            assertNull(local(gone))
            assertNotNull(local(kept))
            assertNotNull(local(sub))
            assertNotNull(local(other))
            assertNotNull(local(root))
        }

    @Test
    fun pendingRowsOfTheFolderAreUntouched() =
        runTest {
            val dirty = server.addNote("Dirty", "d", category = "journal")
            val deleted = server.addNote("Deleted", "x", category = "journal")
            val edited = server.addNote("Edited", "e", category = "journal")
            fullSync()
            notes.markDirty("local edit", clock, local(dirty)!!.local_id)
            notes.markDeleted(local(deleted)!!.local_id)
            notes.insertNew("New", "journal", "fresh", clock, null)
            val newId = notes.lastInsertedLocalId().executeAsOne()
            val dirtyBefore = local(dirty)
            val deletedBefore = local(deleted)
            val newBefore = notes.selectByLocalId(newId).executeAsOne()

            server.deleteOnServer(dirty)
            server.editOnServer(deleted, content = "server edit", title = "Renamed")
            server.editOnServer(edited, content = "server edit")
            val result = assertIs<FolderRefresh.Refreshed>(folderSync.run("journal"))

            assertEquals(FolderRefresh.Refreshed(upserted = 1, deleted = 0), result)
            assertEquals(dirtyBefore, local(dirty))
            assertEquals(deletedBefore, local(deleted))
            assertEquals(newBefore, notes.selectByLocalId(newId).executeAsOne())
            // The SYNCED row with a new etag loses its content, for the next Sync to fetch.
            assertNull(local(edited)!!.content)
        }

    @Test
    fun hiddenIsSetForAHiddenFolder() =
        runTest {
            exclusions.add(ExclusionKind.HIDDEN, "private")
            val a = server.addNote("A", "a", category = "private/sub")
            val b = server.addNote("B", "b", category = "public")

            folderSync.run("private/sub")
            folderSync.run("public")

            assertTrue(local(a)!!.hidden)
            assertFalse(local(b)!!.hidden)
        }

    @Test
    fun changingTheExclusionsClearsTheFolderKeys() =
        runTest {
            server.addNote("A", "a", category = "journal")
            server.addNote("B", "b")
            folderSync.run("journal")
            folderSync.run("")
            fullSync()
            assertNotNull(storedEtag("journal"))
            assertNotNull(storedEtag(""))

            exclusions.add(ExclusionKind.UNSYNCED, "archives")
            assertNull(storedEtag("journal"))
            assertNull(storedEtag(""))
            // The full list's ETag is not a Folder key.
            assertNotNull(db.syncStateQueries.select(PullSync.KEY_LIST_PLAIN).executeAsOneOrNull())

            server.clearRequests()
            assertIs<FolderRefresh.Refreshed>(folderSync.run("journal"))
            assertEquals(200, server.requests.single().status)

            exclusions.remove(ExclusionKind.UNSYNCED, "archives")
            assertNull(storedEtag("journal"))
            exclusions.add(ExclusionKind.HIDDEN, "journal")
            folderSync.run("journal")
            exclusions.remove(ExclusionKind.HIDDEN, "journal")
            assertNull(storedEtag("journal"))
        }

    @Test
    fun anUnsyncedFolderMakesNoRequest() =
        runTest {
            exclusions.add(ExclusionKind.UNSYNCED, "archives")
            server.addNote("A", "a", category = "archives/2020")

            assertEquals(FolderRefresh.Unsynced, folderSync.run("archives"))
            assertEquals(FolderRefresh.Unsynced, folderSync.run("archives/2020"))
            assertEquals(0, server.requests.size)
            assertEquals(0L, notes.countNotes().executeAsOne())
            assertNull(storedEtag("archives"))
        }

    @Test
    fun offlineFailsAndWritesNothing() =
        runTest {
            server.addNote("A", "a", category = "journal")
            server.offline = true

            val failed = assertIs<FolderRefresh.Failed>(folderSync.run("journal"))
            assertIs<ApiError.Network>(failed.error)
            assertEquals(0L, notes.countNotes().executeAsOne())
            assertNull(storedEtag("journal"))
        }

    @Test
    fun fetchContentDownloadsOneNote() =
        runTest {
            val a = server.addNote("A", "text of a", category = "journal")
            folderSync.run("journal")
            val localId = local(a)!!.local_id

            assertTrue(engine.fetchContent(localId))
            assertEquals("text of a", local(a)!!.content)
            assertEquals(1, server.count(Endpoint.GET_NOTE))
        }

    @Test
    fun refreshFolderWaitsForARunningSyncAndNeverShowsSyncing() =
        runTest {
            server.addNote("A", "a", category = "journal")
            val open = CompletableDeferred<Unit>()
            gate = open
            val sync = launch { engine.sync(SyncTrigger.Manual) }
            runCurrent()
            assertIs<SyncStatus.Running>(engine.state.value)

            val refresh = async { engine.refreshFolder("journal") }
            runCurrent()
            // Waiting on the mutex: no request of its own yet, and no AlreadyRunning.
            assertFalse(refresh.isCompleted)
            assertEquals(0, server.count(Endpoint.LIST))

            open.complete(Unit)
            sync.join()
            assertIs<FolderRefresh.Refreshed>(refresh.await())
            val lists = server.requests.filter { it.endpoint == Endpoint.LIST }
            assertEquals("journal", lists.last().query["category"])
            assertIs<SyncStatus.Idle>(engine.state.value)

            // Alone, a refresh never moves the state to Running.
            val seen = mutableListOf<SyncStatus>()
            val collector = launch(UnconfinedTestDispatcher(testScheduler)) { engine.state.collect { seen += it } }
            server.addNote("B", "b", category = "journal")
            assertIs<FolderRefresh.Refreshed>(engine.refreshFolder("journal"))
            collector.cancelAndJoin()
            assertTrue(seen.none { it is SyncStatus.Running })
        }

    @Test
    fun aPushRequestedDuringARefreshRunsAfterIt() =
        runTest {
            val a = server.addNote("A", "a", category = "journal")
            fullSync()
            notes.markDirty("edited", clock, local(a)!!.local_id)
            val open = CompletableDeferred<Unit>()
            gate = open
            val refresh = async { engine.refreshFolder("journal") }
            runCurrent()

            assertEquals(SyncOutcome.AlreadyRunning, engine.push(SyncTrigger.AfterEdit))
            open.complete(Unit)
            assertIs<FolderRefresh.Refreshed>(refresh.await())

            assertEquals("edited", server.note(a)!!.content)
            assertEquals("SYNCED", local(a)!!.sync_state)
        }
}
