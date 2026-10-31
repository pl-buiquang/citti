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
import app.citti.core.sync.FolderSync
import app.citti.core.sync.PullSync
import app.citti.core.sync.PushSync
import app.citti.core.sync.SyncEngine
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NoteCreatorTest {
    private val scheduler = TestCoroutineScheduler()

    // The fake answers on the test scheduler, so a timeout can't fire while a response is on another thread.
    private val server = FakeNotesServer().also { it.engine.config.dispatcher = StandardTestDispatcher(scheduler) }
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val db: CittiDatabase = CittiDatabase.Schema.create(driver).let { CittiDatabase(driver) }
    private val notes = db.noteQueries
    private val exclusions = ExclusionRepository(db)
    private val clock = 1_793_448_000L

    /** How long the server takes to answer, in virtual time. */
    private var serverDelay = 0L

    private val config = NotesApiConfig("https://host/", "alice", "s3cret")
    private val api =
        NotesApi(
            createNotesHttpClient(server.engine, config).also { client ->
                client.plugin(HttpSend).intercept { request ->
                    delay(serverDelay)
                    execute(request)
                }
            },
            config,
        )
    private val engine =
        SyncEngine(
            PushSync(api, db) { clock },
            PullSync(api, db),
            db,
            sleep = {},
            folderSync = FolderSync(db, api, exclusions),
        ) { clock }
    private var pushes = 0

    // The test scope, not backgroundScope: advanceUntilIdle must run the background refresh too.
    private fun TestScope.creator() = NoteCreator(db, engine, this, exclusions, { pushes++ }, { clock })

    private fun test(body: suspend TestScope.() -> Unit) = runTest(StandardTestDispatcher(scheduler)) { body() }

    private fun lists() = server.requests.filter { it.endpoint == Endpoint.LIST }

    private fun storedEtag(folder: String) =
        db.syncStateQueries
            .select(FolderSync.keyOf(folder))
            .executeAsOneOrNull()
            ?.etag

    @Test
    fun aLocalHitIsExistsAtOnceAndStillRefreshesTheFolder() =
        test {
            server.addNote("T", "t", category = "journal")
            notes.insertNew("T", "journal", "", clock, null)
            val local = notes.lastInsertedLocalId().executeAsOne()
            serverDelay = 1_000

            assertEquals(NoteCreator.Check.Exists(local), creator().check("journal", "T"))
            assertEquals(0L, currentTime)

            advanceUntilIdle()
            assertEquals("journal", lists().single().query["category"])
            assertNotNull(storedEtag("journal"))
        }

    @Test
    fun aMissIsFreeWhenTheServerAnswersInTime() =
        test {
            server.addNote("Other", "o", category = "journal")
            serverDelay = 1_000

            assertEquals(NoteCreator.Check.Free, creator().check("journal", "T"))
            assertEquals(1_000L, currentTime)
            assertEquals(1, lists().size)
        }

    @Test
    fun aMissIsExistsWhenTheServerHasTheNote() =
        test {
            val remote = server.addNote("T", "t", category = "journal")
            serverDelay = 1_000

            val check = creator().check("journal", "T")

            val local = notes.selectByRemoteId(remote).executeAsOne().local_id
            assertEquals(NoteCreator.Check.Exists(local), check)
        }

    @Test
    fun theTitleMatchIsCaseSensitiveLikeTheServer() =
        test {
            server.addNote("T", "t", category = "journal")
            assertEquals(NoteCreator.Check.Free, creator().check("journal", "t"))
            // A Note of another Folder, sub-Folder included, is no clash either.
            server.addNote("U", "u", category = "journal/2026")
            assertEquals(NoteCreator.Check.Free, creator().check("journal", "U"))
        }

    @Test
    fun aSlowServerIsUncheckedAfterTheCapAndTheRefreshStillLands() =
        test {
            server.addNote("T", "t", category = "journal")
            serverDelay = 5_000

            assertEquals(NoteCreator.Check.Unchecked, creator().check("journal", "T"))
            assertEquals(CREATION_CHECK_MS, currentTime)
            assertNull(storedEtag("journal"))

            advanceUntilIdle()
            assertNotNull(storedEtag("journal"))
            assertEquals(1, lists().size)
        }

    @Test
    fun offlineIsUncheckedAtOnce() =
        test {
            server.offline = true

            assertEquals(NoteCreator.Check.Unchecked, creator().check("journal", "T"))
            assertEquals(0L, currentTime)
        }

    @Test
    fun anUnsyncedFolderIsUncheckedWithNoRequest() =
        test {
            exclusions.add(ExclusionKind.UNSYNCED, "archive")

            assertEquals(NoteCreator.Check.Unchecked, creator().check("archive", "T"))
            assertTrue(server.requests.isEmpty())
        }

    @Test
    fun freeTitleCountsUpOverExactTitles() =
        test {
            val creator = creator()
            assertEquals("T", creator.freeTitle("journal", "T"))
            notes.insertNew("T", "journal", "", clock, null)
            assertEquals("T (2)", creator.freeTitle("journal", "T"))
            notes.insertNew("T (2)", "journal", "", clock, null)
            assertEquals("T (3)", creator.freeTitle("journal", "T"))
            // Exact match: `t` next to `T` is free, and other Folders don't count.
            assertEquals("t", creator.freeTitle("journal", "t"))
            assertEquals("T", creator.freeTitle("projects", "T"))
            // A DELETED row no longer holds its title.
            notes.insertNew("D", "journal", "", clock, null)
            notes.markDeleted(notes.lastInsertedLocalId().executeAsOne())
            assertEquals("D", creator.freeTitle("journal", "D"))
        }

    @Test
    fun createInsertsAnUntouchedNewNoteAndSchedulesAPush() =
        test {
            exclusions.add(ExclusionKind.HIDDEN, "secret")
            val creator = creator()

            val id = creator.create("secret/sub", "T", "# T\n")

            val row = notes.selectByLocalId(id).executeAsOne()
            assertEquals("NEW", row.sync_state)
            assertEquals("T", row.title)
            assertEquals("secret/sub", row.category)
            assertEquals("# T\n", row.content)
            assertEquals("# T\n", row.created_from)
            assertEquals(clock, row.modified)
            assertNull(row.remote_id)
            assertTrue(row.hidden)
            assertEquals(1, pushes)

            val plain = notes.selectByLocalId(creator.create("", "Empty", "")).executeAsOne()
            assertEquals("", plain.created_from)
            assertEquals(false, plain.hidden)
            assertEquals(2, pushes)
        }

    @Test
    fun createRefusesInvalidNamesAndUnsyncedFolders() =
        test {
            exclusions.add(ExclusionKind.UNSYNCED, "archive")
            val creator = creator()

            assertFailsWith<IllegalArgumentException> { creator.create("journal", "a/b", "") }
            assertFailsWith<IllegalArgumentException> { creator.create("archive/2025", "T", "") }
            assertFailsWith<IllegalArgumentException> { creator.create("/journal/", "T", "") }
            assertEquals(0L, notes.countNotes().executeAsOne())
            assertEquals(0, pushes)
        }
}
