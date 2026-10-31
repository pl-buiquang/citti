package app.citti.core.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.api.ApiError
import app.citti.core.api.NotesApi
import app.citti.core.api.NotesApiConfig
import app.citti.core.api.createNotesHttpClient
import app.citti.core.db.CittiDatabase
import app.citti.core.fake.Endpoint
import app.citti.core.fake.FakeFailure
import app.citti.core.fake.FakeNotesServer
import app.citti.core.fake.HeaderCasing
import app.citti.core.log.SyncLog
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PullSyncTest {
    private val server = FakeNotesServer()
    private val db: CittiDatabase =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let {
            CittiDatabase.Schema.create(it)
            CittiDatabase(it)
        }
    private val notes = db.noteQueries

    private val sleeps = mutableListOf<Long>()
    private val logLines = mutableListOf<String>()
    private val recordingLog =
        object : SyncLog {
            override fun info(msg: String) {
                logLines += msg
            }

            override fun warn(msg: String) {
                logLines += msg
            }

            override fun error(
                msg: String,
                cause: Throwable?,
            ) {
                logLines += msg
            }
        }

    /** Called with the number of note downloads seen so far, before every request; flips the server mid-pull. */
    private var onRequest: (Int) -> Unit = {}

    private fun pullSync(
        chunkSize: Int = 500,
        parallelism: Int = PullSync.DEFAULT_PARALLELISM,
    ): PullSync {
        val config = NotesApiConfig("https://host/", "alice", "s3cret")
        val client = createNotesHttpClient(server.engine, config)
        client.plugin(HttpSend).intercept { request ->
            onRequest(server.count(Endpoint.GET_NOTE))
            execute(request)
        }
        return PullSync(
            NotesApi(client, config),
            db,
            chunkSize = chunkSize,
            downloadParallelism = parallelism,
            log = recordingLog,
            sleep = { sleeps += it },
        )
    }

    private fun byRemote(id: Long) = notes.selectByRemoteId(id).executeAsOneOrNull()

    private fun getRequests() = server.requests.filter { it.endpoint == Endpoint.GET_NOTE }

    @Test
    fun firstSyncPagesInChunksAndIgnoresStubs() =
        runTest {
            server.generateNotes(2300)
            val result = pullSync(chunkSize = 500).pull()

            val pulled = assertIs<PullResult.Pulled>(result)
            assertEquals(2300, pulled.contentFetched)
            assertTrue(pulled.failures.isEmpty())
            assertEquals(2300L, notes.countNotes().executeAsOne())
            assertEquals(2300L, notes.countNotesWithContent().executeAsOne())

            val chunked = server.requests.filter { it.endpoint == Endpoint.LIST && it.query.containsKey("chunkSize") }
            assertEquals(5, chunked.size)
            // Pages after the first carry a cursor, and so receive stubs for the already seen Notes.
            assertTrue(chunked.drop(1).all { it.query.containsKey("chunkCursor") })
            assertNotNull(
                db.syncStateQueries
                    .select("list-plain")
                    .executeAsOne()
                    .etag,
            )
        }

    @Test
    fun secondRunIsOneRequestAnd304() =
        runTest {
            server.generateNotes(300)
            pullSync(100).pull()
            server.clearRequests()

            assertEquals(PullResult.NothingChanged, pullSync(100).pull())
            assertEquals(1, server.requests.size)
            assertEquals(304, server.requests.single().status)
        }

    @Test
    fun lowercaseHeadersWork() =
        runTest {
            server.headerCasing = HeaderCasing.LOWERCASE
            server.generateNotes(250)
            val first = assertIs<PullResult.Pulled>(pullSync(100).pull())
            assertEquals(250, first.contentFetched)
            server.clearRequests()
            assertEquals(PullResult.NothingChanged, pullSync(100).pull())
            assertEquals(1, server.requests.size)
        }

    @Test
    fun editedNoteOnlyFetchesThatContent() =
        runTest {
            val ids = server.generateNotes(100)
            pullSync(40).pull()
            server.advance(10)
            server.editOnServer(ids[7], content = "edited", title = "Renamed")
            server.clearRequests()

            val pulled = assertIs<PullResult.Pulled>(pullSync(40).pull())
            assertEquals(1, pulled.contentFetched)
            assertEquals(listOf("/index.php/apps/notes/api/v1/notes/${ids[7]}"), getRequests().map { it.path })
            val row = byRemote(ids[7])!!
            assertEquals("edited", row.content)
            assertEquals("Renamed", row.title)
            // and the next run is a 304 again
            server.clearRequests()
            assertEquals(PullResult.NothingChanged, pullSync(40).pull())
        }

    @Test
    fun noteDeletedOnServerIsRemovedLocally() =
        runTest {
            val ids = server.generateNotes(50)
            pullSync(20).pull()
            server.deleteOnServer(ids[3])

            assertIs<PullResult.Pulled>(pullSync(20).pull())
            assertNull(byRemote(ids[3]))
            assertEquals(49L, notes.countNotes().executeAsOne())
        }

    @Test
    fun unsyncedFolderIsNeverStoredNorFetched() =
        runTest {
            val secret = server.addNote("S", "secret", category = "priv/inner")
            val sibling = server.addNote("T", "kept", category = "privacy")
            val plain = server.addNote("P", "plain", category = "pub")
            db.settingQueries.insertExclusion("priv", "UNSYNCED")

            val pulled = assertIs<PullResult.Pulled>(pullSync().pull())

            assertEquals(2, pulled.contentFetched)
            assertNull(byRemote(secret))
            assertNotNull(byRemote(sibling))
            assertNotNull(byRemote(plain))
            assertTrue(getRequests().none { it.path.endsWith("/notes/$secret") })
            assertEquals(2L, notes.countNotes().executeAsOne())
        }

    @Test
    fun newlyAddedUnsyncedFolderDeletesStoredNotesButNotDirtyOnes() =
        runTest {
            val a = server.addNote("A", "a", category = "priv")
            val b = server.addNote("B", "b", category = "priv/sub")
            val keep = server.addNote("K", "k", category = "other")
            pullSync().pull()
            val dirty = byRemote(b)!!
            notes.markDirty("local edit", 5L, dirty.local_id)
            db.settingQueries.insertExclusion("priv", "UNSYNCED")

            assertIs<PullResult.Pulled>(pullSync().pull())

            assertNull(byRemote(a))
            assertNotNull(byRemote(keep))
            val survivor = byRemote(b)!!
            assertEquals("DIRTY", survivor.sync_state)
            assertEquals("local edit", survivor.content)
        }

    @Test
    fun hiddenFolderIsStoredFlaggedWithContent() =
        runTest {
            val h = server.addNote("H", "hidden text", category = "attic/deep")
            val v = server.addNote("V", "visible", category = "attics")
            db.settingQueries.insertExclusion("attic", "HIDDEN")

            assertIs<PullResult.Pulled>(pullSync().pull())

            val hidden = byRemote(h)!!
            assertTrue(hidden.hidden)
            assertEquals("hidden text", hidden.content)
            assertFalse(byRemote(v)!!.hidden)
        }

    @Test
    fun changingExclusionsSkipsThe304() =
        runTest {
            val h = server.addNote("H", "x", category = "attic")
            pullSync().pull()
            assertFalse(byRemote(h)!!.hidden)

            db.settingQueries.insertExclusion("attic", "HIDDEN")
            assertIs<PullResult.Pulled>(pullSync().pull())
            assertTrue(byRemote(h)!!.hidden)
            assertEquals(PullResult.NothingChanged, pullSync().pull())
        }

    @Test
    fun listFailureLeavesDbAndEtagUntouched() =
        runTest {
            server.generateNotes(30)
            pullSync().pull()
            val etag = db.syncStateQueries.select("list-plain").executeAsOne()
            server.advance(5)
            server.editOnServer(server.notes.first().id, content = "new")
            server.failNext(FakeFailure.SERVER_ERROR)

            val result = pullSync().pull()

            assertEquals(PullResult.Failed(ApiError.Server(500)), result)
            assertEquals(etag, db.syncStateQueries.select("list-plain").executeAsOne())
            assertEquals(30L, notes.countNotes().executeAsOne())
            assertTrue(notes.selectNeedingContent().executeAsList().isEmpty())
        }

    @Test
    fun firstSyncListFailureStoresNoEtag() =
        runTest {
            server.generateNotes(30)
            server.failNext(FakeFailure.NETWORK_ERROR)

            assertIs<PullResult.Failed>(pullSync().pull())
            assertNull(db.syncStateQueries.select("list-plain").executeAsOneOrNull())
        }

    @Test
    fun dirtyNoteSurvivesServerEditAndServerDelete() =
        runTest {
            val edited = server.addNote("E", "server v1", category = "x")
            val removed = server.addNote("R", "server v1", category = "x")
            pullSync().pull()
            for (id in listOf(edited, removed)) notes.markDirty("mine $id", 99L, byRemote(id)!!.local_id)
            val before = byRemote(edited)!!
            server.advance(10)
            server.editOnServer(edited, content = "server v2")
            server.deleteOnServer(removed)

            assertIs<PullResult.Pulled>(pullSync().pull())

            assertEquals(before, byRemote(edited))
            assertEquals("mine $removed", byRemote(removed)!!.content)
            assertEquals("DIRTY", byRemote(removed)!!.sync_state)
        }

    @Test
    fun progressReachesTotal() =
        runTest {
            server.generateNotes(120)
            val seen = mutableListOf<Progress>()
            pullSync(50).pull { seen += it }
            assertEquals(Progress(120, 120), seen.last())
            assertEquals(120, seen.size)
        }

    @Test
    fun transientContentFailuresAreRetriedAndSucceed() =
        runTest {
            server.generateNotes(20)
            // The first Note fails twice on the network, the Note downloaded 10th hits one 500.
            server.failNext(FakeFailure.NETWORK_ERROR, times = 2, endpoint = Endpoint.GET_NOTE)
            onRequest = { if (it == 10) server.failNext(FakeFailure.SERVER_ERROR, endpoint = Endpoint.GET_NOTE) }

            val pulled = assertIs<PullResult.Pulled>(pullSync(parallelism = 1).pull())

            assertEquals(20, pulled.contentFetched)
            assertTrue(pulled.failures.isEmpty())
            assertEquals(20L, notes.countNotesWithContent().executeAsOne())
            assertEquals(23, getRequests().size)
            assertEquals(3, sleeps.size)
            assertTrue(logLines.any { it.startsWith("pull content retry") })
            assertNotNull(
                db.syncStateQueries
                    .select("list-plain")
                    .executeAsOne()
                    .etag,
            )
        }

    @Test
    fun retriesStopAfterTwoAndTheNoteCountsAsFailed() =
        runTest {
            val id = server.addNote("A", "a")
            server.failNext(FakeFailure.SERVER_ERROR, times = 3, endpoint = Endpoint.GET_NOTE)

            val pulled = assertIs<PullResult.Pulled>(pullSync().pull())

            assertEquals(listOf(500L, 1500L), sleeps)
            assertEquals(3, getRequests().size)
            assertEquals(listOf(id), pulled.failures.map { it.remoteId })
        }

    @Test
    fun sustainedOutageStopsEarlyKeepsWrittenNotesAndNextPullResumes() =
        runTest {
            server.generateNotes(1000)
            // The network dies after 100 note downloads, like Android blocking the app when the screen goes off.
            onRequest = { if (it == 100) server.failNext(FakeFailure.NETWORK_ERROR, times = 100_000, endpoint = Endpoint.GET_NOTE) }

            val result = pullSync(parallelism = 1).pull()

            assertIs<ApiError.Network>(assertIs<PullResult.Failed>(result).error)
            assertEquals(100L, notes.countNotesWithContent().executeAsOne())
            // 5 Notes x (1 attempt + 2 retries) after the 100 good ones; nowhere near the 900 remaining.
            // (a request already in flight when the breaker opens may add one)
            assertTrue(getRequests().size in 115..116)
            assertTrue(logLines.any { it.startsWith("pull content stopped") })
            // The ETag stays withheld so the next pull does not turn into a 304.
            assertNull(
                db.syncStateQueries
                    .select("list-plain")
                    .executeAsOneOrNull()
                    ?.etag,
            )

            // The network is back: only the Notes still missing content are fetched.
            onRequest = {}
            server.clearFailures()
            server.clearRequests()
            val resumed = assertIs<PullResult.Pulled>(pullSync(parallelism = 1).pull())
            assertEquals(900, resumed.contentFetched)
            assertEquals(900, getRequests().size)
            assertEquals(0, notes.selectNeedingContent().executeAsList().size)
            assertEquals(1000L, notes.countNotesWithContent().executeAsOne())
        }

    @Test
    fun scatteredNetworkFailuresDoNotTripTheBreaker() =
        runTest {
            server.generateNotes(30)
            // Each of these Notes exhausts its retries, but successes in between reset the streak.
            onRequest = { if (it % 9 == 0) server.failNext(FakeFailure.NETWORK_ERROR, times = 3, endpoint = Endpoint.GET_NOTE) }

            val pulled = assertIs<PullResult.Pulled>(pullSync(parallelism = 1).pull())

            assertTrue(pulled.failures.isNotEmpty())
            assertEquals(30, pulled.contentFetched + pulled.failures.size)
        }

    @Test
    fun authErrorAbortsTheContentPhaseAtOnce() =
        runTest {
            server.generateNotes(50)
            server.failNext(FakeFailure.UNAUTHORIZED, times = 1000, endpoint = Endpoint.GET_NOTE)

            val result = pullSync(parallelism = 1).pull()

            assertEquals(ApiError.Auth(401), assertIs<PullResult.Failed>(result).error)
            assertTrue(getRequests().size <= 2)
            assertTrue(sleeps.isEmpty())
        }
}
