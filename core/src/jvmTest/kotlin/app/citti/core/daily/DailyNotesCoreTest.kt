package app.citti.core.daily

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.api.NotesApi
import app.citti.core.api.NotesApiConfig
import app.citti.core.api.createNotesHttpClient
import app.citti.core.dav.DavClient
import app.citti.core.dav.DavRoot
import app.citti.core.db.CittiDatabase
import app.citti.core.fake.Endpoint
import app.citti.core.fake.FakeNotesServer
import app.citti.core.insertNew
import app.citti.core.note.CREATION_CHECK_MS
import app.citti.core.note.NameError
import app.citti.core.note.NoteCreator
import app.citti.core.note.NoteEditor
import app.citti.core.settings.DailySettings
import app.citti.core.settings.ExclusionKind
import app.citti.core.settings.ExclusionRepository
import app.citti.core.settings.KEY_DAILY_FORMAT
import app.citti.core.settings.TemplateCache
import app.citti.core.sync.FolderSync
import app.citti.core.sync.PullSync
import app.citti.core.sync.PushSync
import app.citti.core.sync.SyncEngine
import app.citti.core.sync.SyncTrigger
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** [DailyNotes] and [TemplateSource] against the fake server, in virtual time. The E2E flow is `DailyNotesTest`. */
@OptIn(ExperimentalCoroutinesApi::class)
class DailyNotesCoreTest {
    private val scheduler = TestCoroutineScheduler()

    // The fake answers on the test scheduler, so a timeout can't fire while a response is on another thread.
    private val server = FakeNotesServer().also { it.engine.config.dispatcher = StandardTestDispatcher(scheduler) }
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val db: CittiDatabase = CittiDatabase.Schema.create(driver).let { CittiDatabase(driver) }
    private val notes = db.noteQueries
    private val exclusions = ExclusionRepository(db, Dispatchers.Unconfined)
    private val settings = DailySettings(db, exclusions, Dispatchers.Unconfined)
    private val seconds = 1_793_448_000L

    /** How long the server takes to answer, in virtual time. */
    private var serverDelay = 0L

    private var now = Instant.parse("2026-10-31T08:00:00Z")
    private var zone = TimeZone.of("Europe/Paris")
    private var phone = "en"

    private val config = NotesApiConfig("https://host/", "alice", "s3cret")
    private val client =
        createNotesHttpClient(server.engine, config).also { client ->
            client.plugin(HttpSend).intercept { request ->
                delay(serverDelay)
                execute(request)
            }
        }
    private val api = NotesApi(client, config)
    private val dav = DavClient(client, { DavRoot("https://host/", "alice", "Notes") })
    private val engine =
        SyncEngine(
            PushSync(api, db) { seconds },
            PullSync(api, db),
            db,
            sleep = {},
            folderSync = FolderSync(db, api, exclusions),
        ) { seconds }
    private val locales = LocaleDetector(db, settings) { phone }

    // The test scope as the app scope: advanceUntilIdle must run the background work too.
    private fun TestScope.dailyNotes(): DailyNotes {
        val creator = NoteCreator(db, engine, this, exclusions, {}, { seconds })
        val templates = TemplateSource(db, api, dav, settings, engine::fetchContent, this)
        return DailyNotes(db, settings, creator, templates, locales, engine::fetchContent, exclusions, this, { now }, { zone })
    }

    private fun test(body: suspend TestScope.() -> Unit) = runTest(StandardTestDispatcher(scheduler)) { body() }

    private fun count(endpoint: Endpoint) = server.requests.count { it.endpoint == endpoint }

    private fun row(localId: Long) = notes.selectByLocalId(localId).executeAsOne()

    private fun journalTitles() = server.notes.filter { it.category == "journal" }.map { it.title }

    @Test
    fun exitCriterionTheServersNoteOpensInsteadOfADuplicate() =
        test {
            val remote = server.addNote("2026-10-31", "# Made in Obsidian\n", category = "journal")
            val daily = dailyNotes()

            val result = daily.openOrCreate(LocalDate(2026, 10, 31))

            val local = notes.selectByRemoteId(remote).executeAsOne().local_id
            assertEquals(DailyResult.Opened(local), result)
            advanceUntilIdle()
            assertEquals(0, count(Endpoint.CREATE))
            assertEquals(listOf("2026-10-31"), journalTitles())
            assertEquals(1L, notes.countNotes().executeAsOne())
            // Its text, not in the list, is fetched in the background.
            assertEquals("# Made in Obsidian\n", row(local).content)
        }

    @Test
    fun exitCriterionANewNoteRendersTheVaultTemplateInTheDetectedLocale() =
        test {
            // Phone in English, journal written in French.
            server.addNote(
                "2026-07-14",
                "# 2026-07-14  [[journal/2026-W29|Week 29]] | [[journal/2026-juillet|juillet]]\n",
                category = "journal",
            )
            server.addNote("2026-08-15", "# 2026-08-15  [[journal/2026-W33|Week 33]] | [[journal/2026-août|août]]\n", category = "journal")
            server.addNote(
                "2026-09-21",
                "# 2026-09-21  [[journal/2026-W39|Week 39]] | [[journal/2026-septembre|septembre]]\n",
                category = "journal",
            )
            server.addNote("daily", DAILY, category = "templates")
            engine.sync(SyncTrigger.Manual)
            server.clearRequests()
            serverDelay = 500
            val daily = dailyNotes()

            val created = assertIs<DailyResult.Created>(daily.openOrCreate(LocalDate(2026, 10, 31)))

            assertNull(created.templateMissing)
            val row = row(created.localId)
            assertEquals("2026-10-31", row.title)
            assertEquals("journal", row.category)
            assertEquals("NEW", row.sync_state)
            assertEquals(
                "# 2026-10-31  [[journal/2026-W44|Week 44]] | [[journal/2026-octobre|octobre]] | [[journal/2026-Q4|Q4]] | " +
                    "[[journal/2026|2026]]",
                row.content!!.lines().first(),
            )
            assertEquals(DAILY.substringAfter('\n'), row.content!!.substringAfter('\n'))
            assertEquals(row.content, row.created_from)
            assertEquals("fr", settings.config().localeDetected)
            // One list request (the Folder refresh); the Template is local.
            assertEquals(1, count(Endpoint.LIST))
            assertEquals(0, count(Endpoint.GET_NOTE) + count(Endpoint.DAV_GET))
            assertEquals(500L, currentTime)
        }

    @Test
    fun aLocalHitOpensWithoutWaiting() =
        test {
            notes.insertNew("2026-10-31", "journal", "# Today\n", seconds, null)
            val local = notes.lastInsertedLocalId().executeAsOne()
            serverDelay = 1_000

            assertEquals(DailyResult.Opened(local), dailyNotes().openOrCreate(LocalDate(2026, 10, 31)))
            assertEquals(0L, currentTime)
        }

    @Test
    fun noNoteCreatesOneFromTheLocalTemplate() =
        test {
            settings.setLocale("en")
            settings.setFormat("dddd D MMMM YYYY")
            notes.insertNew("daily", "templates", "# {{title}}\nWeek {{date:w}}\n", seconds, null)

            val created = assertIs<DailyResult.Created>(dailyNotes().openOrCreate(LocalDate(2026, 10, 31)))

            val row = row(created.localId)
            assertEquals("Saturday 31 October 2026", row.title)
            assertEquals("# Saturday 31 October 2026\nWeek 44\n", row.content)
            assertEquals(row.content, row.created_from)
        }

    @Test
    fun aTemplateInAnUnsyncedFolderIsFetchedAndCachedWithoutStoringTheFolder() =
        test {
            exclusions.add(ExclusionKind.UNSYNCED, "templates")
            server.addNote("daily", "# {{title}}\n", category = "templates")
            server.addNote("weekly", "# Week\n", category = "templates")
            val daily = dailyNotes()

            val first = assertIs<DailyResult.Created>(daily.openOrCreate(LocalDate(2026, 10, 31)))

            assertEquals("# 2026-10-31\n", row(first.localId).content)
            assertTrue(server.requests.any { it.endpoint == Endpoint.LIST && it.query["category"] == "templates" })
            assertEquals(1, count(Endpoint.GET_NOTE))
            assertEquals(0, notes.selectJournalNotes("templates").executeAsList().size)
            assertEquals("templates/daily.md", settings.templateCache()?.path)

            // Offline, the next creation uses the cache.
            server.offline = true
            val second = assertIs<DailyResult.Created>(daily.openOrCreate(LocalDate(2026, 11, 1)))
            assertEquals("# 2026-11-01\n", row(second.localId).content)
            assertNull(second.templateMissing)
        }

    @Test
    fun aCachedTemplateIsUsedAtOnceAndRevalidated() =
        test {
            val id = server.addNote("daily", "# New {{title}}\n", category = "templates")
            exclusions.add(ExclusionKind.UNSYNCED, "templates")
            settings.setTemplateCache(TemplateCache("templates/daily.md", "stale", "# Old {{title}}\n"))
            val daily = dailyNotes()

            val first = assertIs<DailyResult.Created>(daily.openOrCreate(LocalDate(2026, 10, 31)))
            assertEquals("# Old 2026-10-31\n", row(first.localId).content)

            advanceUntilIdle()
            assertEquals(TemplateCache("templates/daily.md", server.note(id)!!.etag, "# New {{title}}\n"), settings.templateCache())
            val second = assertIs<DailyResult.Created>(daily.openOrCreate(LocalDate(2026, 11, 1)))
            assertEquals("# New 2026-11-01\n", row(second.localId).content)
        }

    @Test
    fun aTemplateOnlyOnWebDavIsFetchedThere() =
        test {
            server.putFile("templates/daily.md", "# {{title}} (dav)\n".encodeToByteArray())

            val created = assertIs<DailyResult.Created>(dailyNotes().openOrCreate(LocalDate(2026, 10, 31)))

            assertEquals("# 2026-10-31 (dav)\n", row(created.localId).content)
            assertEquals(1, count(Endpoint.DAV_GET))
            assertEquals(server.fileEtag("templates/daily.md"), settings.templateCache()?.etag)
        }

    @Test
    fun aMissingTemplateGivesAnEmptyNoteThatSaysSo() =
        test {
            val daily = dailyNotes()
            val created = assertIs<DailyResult.Created>(daily.openOrCreate(LocalDate(2026, 10, 31)))
            assertEquals("templates/daily.md", created.templateMissing)
            assertEquals("", row(created.localId).content)
            assertEquals("", row(created.localId).created_from)

            // No Template at all is no missing Template.
            settings.setTemplate("")
            val none = assertIs<DailyResult.Created>(daily.openOrCreate(LocalDate(2026, 11, 1)))
            assertNull(none.templateMissing)
            assertEquals("", row(none.localId).content)
        }

    @Test
    fun theTemplateLookupStaysWithinOneCap() =
        test {
            // A local Template whose text isn't local, on a slow server: the fetch and the list each take 2.5 s.
            notes.upsertFromServer(1, "e", "daily", "templates", false, seconds, false, false)
            serverDelay = 2_500
            val slowFetch: suspend (Long) -> Boolean = {
                delay(2_500)
                false
            }
            val templates = TemplateSource(db, api, dav, settings, slowFetch, this, timeSource = testTimeSource)

            assertEquals(TemplateText.Missing("templates/daily.md"), templates.load("templates/daily.md"))
            assertTrue(currentTime <= CREATION_CHECK_MS, "took $currentTime ms")
        }

    @Test
    fun offlineCreatesLocallyAndAnUntouchedDuplicateIsDroppedAtSync() =
        test {
            server.addNote("daily", "# {{title}}\n", category = "templates")
            engine.sync(SyncTrigger.Manual)
            server.offline = true
            val created = assertIs<DailyResult.Created>(dailyNotes().openOrCreate(LocalDate(2026, 10, 31)))
            assertEquals(0L, currentTime)
            assertEquals("NEW", row(created.localId).sync_state)
            assertEquals("# 2026-10-31\n", row(created.localId).created_from)

            server.offline = false
            server.addNote("2026-10-31", "# From Obsidian\n", category = "journal")
            engine.sync(SyncTrigger.Manual)

            assertNull(notes.selectByLocalId(created.localId).executeAsOneOrNull())
            assertEquals(0, count(Endpoint.CREATE))
            assertEquals(listOf("2026-10-31"), journalTitles())
        }

    @Test
    fun anEditedOfflineDuplicateBecomesAConflictCopy() =
        test {
            server.offline = true
            val created = assertIs<DailyResult.Created>(dailyNotes().openOrCreate(LocalDate(2026, 10, 31)))
            NoteEditor(db, created.localId, { seconds }).save("Written offline\n", null)

            server.offline = false
            server.addNote("2026-10-31", "# From Obsidian\n", category = "journal")
            engine.sync(SyncTrigger.Manual)

            val titles = journalTitles()
            assertTrue("2026-10-31" in titles)
            assertTrue(titles.any { it.startsWith("2026-10-31 (conflict ") }, "$titles")
            assertTrue(titles.none { it == "2026-10-31 (2)" })
        }

    @Test
    fun aJournalFolderUnderAnUnsyncedFolderCreatesNothing() =
        test {
            // A state saved before M6: the guard of ExclusionRepository.add didn't exist yet.
            db.settingQueries.insertExclusion("journal", ExclusionKind.UNSYNCED.name)

            assertEquals(DailyResult.JournalUnsynced, dailyNotes().openOrCreate(LocalDate(2026, 10, 31)))
            assertEquals(0L, notes.countNotes().executeAsOne())
            assertTrue(server.requests.isEmpty())
        }

    @Test
    fun aFormatWritingAnInvalidTitleCreatesNothing() =
        test {
            // A value stored without DailySettings.setFormat, which refuses it.
            db.settingQueries.upsertSetting(KEY_DAILY_FORMAT, "YYYY-MM-DD HH:mm")

            assertEquals(
                DailyResult.InvalidTitle("2026-10-31 09:00", NameError.UnsafeCharacter),
                dailyNotes().openOrCreate(LocalDate(2026, 10, 31)),
            )
            assertEquals(0L, notes.countNotes().executeAsOne())
        }

    @Test
    fun todayFollowsTheClockAndTheZone() =
        test {
            val daily = dailyNotes()
            // Paris is UTC+1 after the end of summer time (2026-10-25).
            now = Instant.parse("2026-10-31T22:59:00Z")
            assertEquals(LocalDate(2026, 10, 31), daily.today())
            now = Instant.parse("2026-10-31T23:01:00Z")
            assertEquals(LocalDate(2026, 11, 1), daily.today())
            zone = TimeZone.of("America/New_York")
            assertEquals(LocalDate(2026, 10, 31), daily.today())
        }

    @Test
    fun dateOfAndLocalNoteReadDailyNotesWithoutDetection() =
        test {
            settings.setFormat("D MMMM YYYY")
            phone = "en"
            notes.insertNew("12 octobre 2026", "journal", "# x\n", seconds, null)
            val local = notes.lastInsertedLocalId().executeAsOne()
            val daily = dailyNotes()

            assertEquals(LocalDate(2026, 10, 12), daily.dateOf("journal", "12 octobre 2026"))
            assertEquals(LocalDate(2026, 10, 12), daily.dateOf("journal", "12 October 2026"))
            assertNull(daily.dateOf("work", "12 octobre 2026"))
            assertNull(daily.dateOf("journal", "12 octobre 2026 (conflict 2026-10-13)"))
            assertNull(daily.dateOf("journal", "octobre 2026"))
            assertEquals(local, daily.localNote(LocalDate(2026, 10, 12)))
            assertNull(daily.localNote(LocalDate(2026, 10, 13)))
            assertNull(settings.config().localeDetected)

            // A known Date locale is the only one read.
            settings.setLocale("en")
            assertNull(daily.dateOf("journal", "12 octobre 2026"))
            assertNull(daily.localNote(LocalDate(2026, 10, 12)))
        }

    @Test
    fun aPeriodicNoteIsCreatedEmptyWithTheTitleOfItsFormat() =
        test {
            phone = "fr"
            val daily = dailyNotes()

            val month = assertIs<DailyResult.Created>(daily.openOrCreate(Period.of(PeriodKind.MONTH, LocalDate(2026, 10, 31))))
            val week = assertIs<DailyResult.Created>(daily.openOrCreate(Period.of(PeriodKind.WEEK, LocalDate(2027, 1, 1))))
            val quarter = assertIs<DailyResult.Created>(daily.openOrCreate(Period.of(PeriodKind.QUARTER, LocalDate(2026, 10, 31))))
            val year = assertIs<DailyResult.Created>(daily.openOrCreate(Period.of(PeriodKind.YEAR, LocalDate(2026, 10, 31))))

            assertEquals(
                listOf("2026-octobre", "2026-W53", "2026-Q4", "2026"),
                listOf(month, week, quarter, year).map { row(it.localId).title },
            )
            listOf(month, week, quarter, year).forEach {
                assertEquals("", row(it.localId).content)
                assertEquals("journal", row(it.localId).category)
                assertNull(it.templateMissing)
            }
        }

    @Test
    fun aPeriodicNoteRendersItsOwnTemplateFromTheStartOfThePeriod() =
        test {
            phone = "fr"
            notes.insertNew("month", "templates", "# {{title}}\n{{date:MMMM YYYY}} W{{date:ww}}\n", seconds, null)
            settings.setPeriodTemplate(PeriodKind.MONTH, "templates/month")

            val created = assertIs<DailyResult.Created>(dailyNotes().openOrCreate(Period.of(PeriodKind.MONTH, LocalDate(2026, 10, 31))))

            assertEquals("# 2026-octobre\noctobre 2026 W40\n", row(created.localId).content)
        }

    @Test
    fun aMissingPeriodicTemplateSaysSo() =
        test {
            settings.setPeriodTemplate(PeriodKind.YEAR, "templates/year")

            val created = assertIs<DailyResult.Created>(dailyNotes().openOrCreate(Period.of(PeriodKind.YEAR, LocalDate(2026, 10, 31))))

            assertEquals("templates/year.md", created.templateMissing)
        }

    @Test
    fun theServersPeriodicNoteOpensInsteadOfADuplicate() =
        test {
            val remote = server.addNote("2026-W44", "# Week 44\n", category = "journal")
            val daily = dailyNotes()

            val result = daily.openOrCreate(Period.of(PeriodKind.WEEK, LocalDate(2026, 10, 31)))

            assertEquals(DailyResult.Opened(notes.selectByRemoteId(remote).executeAsOne().local_id), result)
            advanceUntilIdle()
            assertEquals(0, count(Endpoint.CREATE))
        }

    @Test
    fun periodOfNamesTheDailyOrPeriodicNoteATitleIs() =
        test {
            phone = "fr"
            val daily = dailyNotes()

            assertEquals(Period.of(PeriodKind.DAY, LocalDate(2026, 10, 31)), daily.periodOf("journal", "2026-10-31"))
            assertEquals(Period.of(PeriodKind.WEEK, LocalDate(2026, 10, 26)), daily.periodOf("journal", "2026-W44"))
            assertEquals(Period.of(PeriodKind.MONTH, LocalDate(2026, 10, 1)), daily.periodOf("journal", "2026-octobre"))
            assertEquals(Period.of(PeriodKind.QUARTER, LocalDate(2026, 10, 1)), daily.periodOf("journal", "2026-Q4"))
            assertEquals(Period.of(PeriodKind.YEAR, LocalDate(2026, 1, 1)), daily.periodOf("journal", "2026"))
            // The week of 2027-01-01 belongs to 2026.
            assertEquals(Period.of(PeriodKind.WEEK, LocalDate(2026, 12, 28)), daily.periodOf("journal", "2026-W53"))
            assertNull(daily.periodOf("journal", "2027-W53"))
            assertNull(daily.periodOf("journal", "Ideas"))
            assertNull(daily.periodOf("journal", "2026-W44 (conflict 2026-11-02)"))
            assertNull(daily.periodOf("work", "2026-W44"))
        }

    @Test
    fun aPeriodicNoteIsFoundLocally() =
        test {
            phone = "fr"
            notes.insertNew("2026-octobre", "journal", "# Octobre\n", seconds, null)
            val local = notes.lastInsertedLocalId().executeAsOne()
            val daily = dailyNotes()

            assertEquals(local, daily.localNote(Period.of(PeriodKind.MONTH, LocalDate(2026, 10, 12))))
            assertNull(daily.localNote(Period.of(PeriodKind.MONTH, LocalDate(2026, 11, 12))))
        }

    private companion object {
        /** The vault's templates/daily.md, byte for byte (two spaces after the title, a trailing blank line). */
        const val DAILY =
            "# {{title}}  [[journal/{{date:YYYY}}-W{{date:ww}}|Week {{date:ww}}]] | " +
                "[[journal/{{date:YYYY}}-{{date:MMMM}}|{{date:MMMM}}]] | " +
                "[[journal/{{date:YYYY}}-Q{{date:Q}}|Q{{date:Q}}]] | " +
                "[[journal/{{date:YYYY}}|{{date:YYYY}}]]\n" +
                "\n---\n\n\n\n---\n\n"
    }
}
