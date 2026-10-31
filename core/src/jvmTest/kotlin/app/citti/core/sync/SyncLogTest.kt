package app.citti.core.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.api.NotesApi
import app.citti.core.api.NotesApiConfig
import app.citti.core.api.createNotesHttpClient
import app.citti.core.db.CittiDatabase
import app.citti.core.fake.FakeFailure
import app.citti.core.fake.FakeNotesServer
import app.citti.core.log.SyncLog
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SyncLogTest {
    private class Recording : SyncLog {
        val lines = mutableListOf<String>()

        override fun info(msg: String) {
            lines += "I $msg"
        }

        override fun warn(msg: String) {
            lines += "W $msg"
        }

        override fun error(
            msg: String,
            cause: Throwable?,
        ) {
            lines += "E $msg"
        }
    }

    private val password = "app-pw-Zq81Lm"
    private val title = "SecretTitleKiwi"
    private val body = "SecretBodyMango"
    private val category = "SecretCategoryPlum"
    private val clock = { 1_793_448_000L }

    private val server = FakeNotesServer(credentials = "alice" to password)
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val db: CittiDatabase = CittiDatabase.Schema.create(driver).let { CittiDatabase(driver) }
    private val log = Recording()

    private fun engine(sink: SyncLog): SyncEngine {
        val config = NotesApiConfig("https://host/", "alice", password)
        val api = NotesApi(createNotesHttpClient(server.engine, config), config, sink)
        return SyncEngine(PushSync(api, db, sink, clock), PullSync(api, db, log = sink), db, sink, api.stats, clock)
    }

    @Test
    fun firstSyncAndNotModifiedLogASummary() =
        runTest {
            server.addNote(title, body, category)
            db.noteQueries.insertNew("Local$title", category, "Local$body", 1, null)
            val engine = engine(log)

            assertIs<SyncOutcome.Synced>(engine.sync(SyncTrigger.AppOpen))
            val first = log.lines.single { "sync done" in it }
            assertTrue("trigger=AppOpen outcome=Synced pushed=1 pulled=1 conflicts=0 failures=0" in first, first)
            assertTrue(Regex("duration=\\d+ms requests=\\d+ bytes=\\d+").containsMatchIn(first), first)
            assertTrue(log.lines.any { "sync start trigger=AppOpen" in it })
            assertTrue(log.lines.any { "phase push end updated=0 created=1" in it })
            assertTrue(log.lines.any { "req GET /notes?exclude -> 200" in it })

            log.lines.clear()
            assertIs<SyncOutcome.NothingChanged>(engine.sync(SyncTrigger.Manual))
            val second = log.lines.single { "sync done" in it }
            assertTrue("trigger=Manual outcome=NothingChanged" in second, second)
            assertTrue("requests=1 " in second, second)
            assertTrue(log.lines.any { "-> 304 (not modified)" in it })
        }

    @Test
    fun firstPullLogsChunkPages() =
        runTest {
            server.addNote(title, body, category)
            engine(log).sync(SyncTrigger.AppOpen)
            assertTrue(log.lines.any { "pull chunk page=1 notes=1 pending=" in it }, log.lines.toString())
            assertTrue(log.lines.any { "req GET /notes?exclude&chunkSize=500&chunkCursor" !in it && "chunkSize=500 -> 200" in it })
            assertTrue(log.lines.any { "pull content 1/1 " in it })
        }

    @Test
    fun failuresLogKindAndStatusOnly() =
        runTest {
            server.failNext(FakeFailure.SERVER_ERROR)
            engine(log).sync(SyncTrigger.Periodic)
            assertTrue(
                log.lines.any { "sync done trigger=Periodic outcome=Failed error=Server(http 500)" in it },
                log.lines.toString(),
            )
            assertFalse(log.lines.any { "boom" in it })
        }

    @Test
    fun noLineLeaksSecretsTitlesOrContent() =
        runTest {
            server.addNote(title, body, category)
            db.noteQueries.insertNew("Local$title", category, "Local$body", 1, null)
            val engine = engine(log)
            engine.sync(SyncTrigger.Manual)
            engine.sync(SyncTrigger.Manual)
            val text = log.lines.joinToString("\n")
            for (secret in listOf(password, title, body, category, "Authorization", "Basic ", "alice")) {
                assertFalse(secret in text, "log leaked $secret")
            }
            assertTrue(log.lines.isNotEmpty())
        }

    @Test
    fun noOpDefaultBehavesTheSame() =
        runTest {
            server.addNote(title, body, category)
            val quiet = engine(SyncLog.None)
            val outcome = assertIs<SyncOutcome.Synced>(quiet.sync(SyncTrigger.Manual))
            assertEquals(1, outcome.pulled)
            assertIs<SyncOutcome.NothingChanged>(quiet.sync(SyncTrigger.Manual))
        }
}
