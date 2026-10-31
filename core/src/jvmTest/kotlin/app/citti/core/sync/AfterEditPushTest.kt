package app.citti.core.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.api.NotesApi
import app.citti.core.api.NotesApiConfig
import app.citti.core.api.createNotesHttpClient
import app.citti.core.db.CittiDatabase
import app.citti.core.fake.Endpoint
import app.citti.core.fake.FakeFailure
import app.citti.core.fake.FakeNotesServer
import app.citti.core.insertNew
import app.citti.core.markDirty
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class AfterEditPushTest {
    private val server = FakeNotesServer()
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val db: CittiDatabase = CittiDatabase.Schema.create(driver).let { CittiDatabase(driver) }
    private val notes = db.noteQueries
    private var gate: CompletableDeferred<Unit>? = null

    private val engine: SyncEngine =
        run {
            val config = NotesApiConfig("https://host/", "alice", "s3cret")
            val client = createNotesHttpClient(server.engine, config)
            client.plugin(HttpSend).intercept { request ->
                gate?.await()
                execute(request)
            }
            val api = NotesApi(client, config)
            SyncEngine(PushSync(api, db) { 1_793_448_000L }, PullSync(api, db), db) { 1_793_448_000L }
        }

    /** The mock engine answers on real threads, so wait for [condition] in real time. */
    private suspend fun eventually(condition: () -> Boolean) =
        withContext(Dispatchers.Default) {
            repeat(300) {
                if (condition()) return@withContext
                delay(10)
            }
        }

    private fun newLocal(title: String) {
        notes.insertNew(title, "", "text", 1, null)
    }

    @Test
    fun threeSavesWithinTwoSecondsGiveOnePushAfterTheLast() =
        runTest {
            val after = AfterEditPush(backgroundScope, engine)
            newLocal("A")
            after.schedule()
            advanceTimeBy(1_500)
            after.schedule()
            advanceTimeBy(1_500)
            after.schedule()
            advanceTimeBy(1_999)
            runCurrent()
            assertEquals(0, server.count(Endpoint.CREATE))
            advanceTimeBy(2)
            runCurrent()
            eventually { server.count(Endpoint.CREATE) == 1 && engine.state.value is SyncStatus.Idle }
            assertEquals(1, server.count(Endpoint.CREATE))
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(1, server.count(Endpoint.CREATE))
        }

    @Test
    fun pushOnlyListsJustTheFolderOfItsNewNotes() =
        runTest {
            newLocal("A")
            assertIs<SyncOutcome.Synced>(engine.push())
            // The push-time check (ADR 0006): one folder list, never the full list.
            assertEquals(listOf(""), server.requests.filter { it.endpoint == Endpoint.LIST }.map { it.query["category"] })
            assertEquals(1, server.count(Endpoint.CREATE))
            assertEquals(0, engine.state.value.pendingChanges)

            // Without New notes, no list request at all.
            server.clearRequests()
            val synced = notes.selectByRemoteId(server.notes.single().id).executeAsOne().local_id
            notes.markDirty("edited", 2, synced)
            assertIs<SyncOutcome.Synced>(engine.push())
            assertEquals(0, server.count(Endpoint.LIST))
            assertEquals(1, server.count(Endpoint.UPDATE))
        }

    @Test
    fun aPushRequestedWhileASyncRunsFollowsItWhenSomethingIsPending() =
        runTest {
            server.addNote("R", "r")
            val open = CompletableDeferred<Unit>()
            gate = open
            val sync = launch { engine.sync(SyncTrigger.Manual) }
            runCurrent()
            // Typed during the sync, after its push phase read the queue.
            newLocal("Late")
            assertEquals(SyncOutcome.AlreadyRunning, engine.push())
            open.complete(Unit)
            sync.join()
            assertEquals(1, server.count(Endpoint.CREATE))
            assertEquals(0, engine.state.value.pendingChanges)
        }

    @Test
    fun aPushRequestedWhileASyncRunsDoesNothingWhenNothingIsPending() =
        runTest {
            server.addNote("R", "r")
            val open = CompletableDeferred<Unit>()
            gate = open
            val sync = launch { engine.sync(SyncTrigger.Manual) }
            runCurrent()
            engine.push()
            open.complete(Unit)
            sync.join()
            assertEquals(0, server.count(Endpoint.CREATE))
            assertEquals(0, server.count(Endpoint.UPDATE))
        }

    @Test
    fun offlineGivesNoRetryLoop() =
        runTest {
            val after = AfterEditPush(backgroundScope, engine)
            newLocal("A")
            server.failNext(FakeFailure.NETWORK_ERROR, times = 5)
            after.schedule()
            advanceTimeBy(2_100)
            runCurrent()
            eventually { engine.state.value is SyncStatus.Offline }
            advanceTimeBy(60_000)
            runCurrent()
            assertIs<SyncStatus.Offline>(engine.state.value)
            assertEquals(1, engine.state.value.pendingChanges)
            // One attempt, no timer left behind.
            assertEquals(1, server.requests.count { it.status == null })
        }
}
