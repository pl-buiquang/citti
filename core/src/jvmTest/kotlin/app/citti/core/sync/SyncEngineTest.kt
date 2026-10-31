package app.citti.core.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.api.ApiError
import app.citti.core.api.NotesApi
import app.citti.core.api.NotesApiConfig
import app.citti.core.api.createNotesHttpClient
import app.citti.core.db.CittiDatabase
import app.citti.core.fake.FakeFailure
import app.citti.core.fake.FakeNotesServer
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineTest {
    private val server = FakeNotesServer()
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val db: CittiDatabase =
        CittiDatabase.Schema.create(driver).let { CittiDatabase(driver) }
    private val notes = db.noteQueries

    private var clock = 1_793_448_000L

    /** Completes when the test lets HTTP requests through. */
    private var gate: CompletableDeferred<Unit>? = null

    private val engine: SyncEngine = newEngine()

    private fun newEngine(): SyncEngine {
        val config = NotesApiConfig("https://host/", "alice", "s3cret")
        val client = createNotesHttpClient(server.engine, config)
        client.plugin(HttpSend).intercept { request ->
            gate?.await()
            execute(request)
        }
        val api = NotesApi(client, config)
        return SyncEngine(PushSync(api, db) { clock }, PullSync(api, db), db) { clock }
    }

    private fun newLocal(
        title: String,
        content: String,
    ): Long {
        notes.insertNew(title, "", content, 1, null)
        return notes.lastInsertedLocalId().executeAsOne()
    }

    @Test
    fun offlineEditThenOnlineSyncPushesThenPulls() =
        runTest {
            server.addNote("Remote", "r")
            newLocal("Local", "text")

            server.failNext(FakeFailure.NETWORK_ERROR)
            val offline = engine.sync(SyncTrigger.Manual)
            assertIs<SyncOutcome.Failed>(offline)
            assertIs<SyncStatus.Offline>(engine.state.value)
            assertEquals(1, engine.state.value.pendingChanges)

            val outcome = assertIs<SyncOutcome.Synced>(engine.sync(SyncTrigger.Manual))
            assertEquals(1, outcome.pushed)
            assertEquals(1, outcome.pulled)
            assertEquals(2, server.notes.size)
            assertEquals(2L, notes.countNotes().executeAsOne())
            val idle = assertIs<SyncStatus.Idle>(engine.state.value)
            assertEquals(0, idle.pendingChanges)
            assertEquals(SyncResultKind.Synced, idle.lastResult)
        }

    @Test
    fun noChangeRunAfterFirstSyncIsOneRequest() =
        runTest {
            server.generateNotes(20)
            engine.sync(SyncTrigger.AppOpen)
            server.clearRequests()

            assertEquals(SyncOutcome.NothingChanged, engine.sync(SyncTrigger.Periodic))
            assertEquals(1, server.requests.size)
            assertEquals(304, server.requests.single().status)
            assertEquals(SyncResultKind.NothingChanged, assertIs<SyncStatus.Idle>(engine.state.value).lastResult)
        }

    @Test
    fun concurrentCallReturnsAlreadyRunning() =
        runTest {
            server.addNote("A", "a")
            val open = CompletableDeferred<Unit>()
            gate = open
            val first = launch { engine.sync(SyncTrigger.Manual) }
            runCurrent()

            assertEquals(SyncOutcome.AlreadyRunning, engine.sync(SyncTrigger.AfterEdit))
            assertIs<SyncStatus.Running>(engine.state.value)

            open.complete(Unit)
            first.join()
            assertIs<SyncStatus.Idle>(engine.state.value)
        }

    @Test
    fun networkErrorMapsToOffline() =
        runTest {
            server.failNext(FakeFailure.NETWORK_ERROR)
            val outcome = assertIs<SyncOutcome.Failed>(engine.sync(SyncTrigger.Manual))
            assertIs<ApiError.Network>(outcome.error)
            assertIs<SyncStatus.Offline>(engine.state.value)
        }

    @Test
    fun serverErrorMapsToServerError() =
        runTest {
            server.failNext(FakeFailure.SERVER_ERROR)
            val outcome = assertIs<SyncOutcome.Failed>(engine.sync(SyncTrigger.Manual))
            assertIs<ApiError.Server>(outcome.error)
            val status = assertIs<SyncStatus.ServerError>(engine.state.value)
            assertTrue(status.status in 500..599)
        }

    @Test
    fun pullIsSkippedOnPushAuthFailure() =
        runTest {
            newLocal("Local", "text")
            server.failNext(FakeFailure.UNAUTHORIZED)

            val outcome = assertIs<SyncOutcome.Failed>(engine.sync(SyncTrigger.Manual))
            assertIs<ApiError.Auth>(outcome.error)
            assertEquals(1, server.requests.size)
            val status = assertIs<SyncStatus.AuthFailed>(engine.state.value)
            assertEquals(1, status.pendingChanges)
        }

    @Test
    fun lastSyncIsPersistedAndRestored() =
        runTest {
            server.addNote("A", "a")
            engine.sync(SyncTrigger.Manual)

            assertEquals(clock.toString(), db.settingQueries.selectSetting(SyncEngine.KEY_LAST_AT).executeAsOne())
            assertEquals("Synced", db.settingQueries.selectSetting(SyncEngine.KEY_LAST_RESULT).executeAsOne())

            clock += 600
            val restarted = newEngine()
            val idle = assertIs<SyncStatus.Idle>(restarted.state.value)
            assertEquals(clock - 600, idle.lastSuccessAt)
            assertEquals(SyncResultKind.Synced, idle.lastResult)
        }

    @Test
    fun failedRunKeepsLastSuccessAt() =
        runTest {
            engine.sync(SyncTrigger.Manual)
            val at = clock
            clock += 60
            server.failNext(FakeFailure.NETWORK_ERROR)
            engine.sync(SyncTrigger.Manual)

            assertEquals(at, engine.state.value.lastSuccessAt)
            assertEquals("Offline", db.settingQueries.selectSetting(SyncEngine.KEY_LAST_RESULT).executeAsOne())
            assertEquals(at.toString(), db.settingQueries.selectSetting(SyncEngine.KEY_LAST_AT).executeAsOne())
        }

    @Test
    fun stateGoesPushingPullingWithProgressIdle() =
        runTest {
            server.generateNotes(3)
            val seen = mutableListOf<SyncStatus>()
            val collector =
                launch(UnconfinedTestDispatcher(testScheduler)) { engine.state.collect { seen += it } }

            engine.sync(SyncTrigger.AppOpen)
            collector.cancelAndJoin()

            val running = seen.filterIsInstance<SyncStatus.Running>()
            assertEquals(SyncPhase.Pushing, running.first().phase)
            assertTrue(running.any { it.phase == SyncPhase.Pulling && it.progress == null })
            assertEquals(Progress(3, 3), running.last().progress)
            assertEquals(SyncPhase.Pulling, running.last().phase)
            assertIs<SyncStatus.Idle>(seen.last())
            assertIs<SyncStatus.Idle>(seen.first())
            // Pushing comes before Pulling.
            assertTrue(seen.indexOfFirst { it is SyncStatus.Running } < seen.indexOfLast { it is SyncStatus.Running })
        }

    @Test
    fun cancellationResetsStateAndReleasesTheLock() =
        runTest {
            server.addNote("A", "a")
            gate = CompletableDeferred()
            val job = launch { engine.sync(SyncTrigger.Manual) }
            runCurrent()
            assertIs<SyncStatus.Running>(engine.state.value)

            job.cancelAndJoin()
            assertIs<SyncStatus.Idle>(engine.state.value)
            assertNull(engine.state.value.lastSuccessAt)

            gate = null
            assertIs<SyncOutcome.Synced>(engine.sync(SyncTrigger.Manual))
        }

    @Test
    fun conflictCopiesAreCounted() =
        runTest {
            val remote = server.addNote("A", "a")
            engine.sync(SyncTrigger.Manual)
            val local = notes.selectByRemoteId(remote).executeAsOne().local_id
            server.editOnServer(remote, content = "server edit")
            notes.markDirty("local edit", clock, local)

            val outcome = assertIs<SyncOutcome.Synced>(engine.sync(SyncTrigger.Manual))
            assertEquals(1, outcome.conflicts)
            assertEquals(1, engine.state.value.conflictCopies)
        }
}
