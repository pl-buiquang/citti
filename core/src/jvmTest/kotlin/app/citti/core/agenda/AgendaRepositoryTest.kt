package app.citti.core.agenda

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.citti.core.api.ApiError
import app.citti.core.api.NotesApiConfig
import app.citti.core.api.createNotesHttpClient
import app.citti.core.auth.wipeLocalData
import app.citti.core.dav.CalDavClient
import app.citti.core.dav.DavRoot
import app.citti.core.db.CittiDatabase
import app.citti.core.fake.Endpoint
import app.citti.core.fake.FakeNotesServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AgendaRepositoryTest {
    private val db: CittiDatabase =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let {
            CittiDatabase.Schema.create(it)
            CittiDatabase(it)
        }
    private val server = FakeNotesServer(credentials = "alice" to "pw")
    private var now = Instant.parse("2026-11-16T08:00:00Z")
    private val clock =
        object : Clock {
            override fun now() = now
        }
    private var connected = true
    private val calDav =
        CalDavClient(createNotesHttpClient(server.engine, NotesApiConfig("https://host/", "alice", "pw")), {
            DavRoot("https://host/", "alice", "Notes")
        })

    private fun event(
        uid: String,
        day: Int,
    ) = "BEGIN:VEVENT\nUID:$uid\nDTSTART:202611${day}T090000Z\nDTEND:202611${day}T100000Z\nSUMMARY:$uid\nEND:VEVENT"

    init {
        server.addCalendar("personal", "Personal", events = listOf(event("a", 16), event("b", 17)))
        server.addCalendar("work", "Work", events = listOf(event("w", 16)))
    }

    private val personal = server.calendarHref("personal")
    private val work = server.calendarHref("work")

    private fun request(
        day: Int = 16,
        calendars: List<String> = listOf(personal, work),
    ) = AgendaRequest(calendars, LocalDate(2026, 11, day), 1, "Europe/Paris")

    private fun TestScope.repository(maxRows: Long = 200): AgendaRepository {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return AgendaRepository(db, { calDav.takeIf { connected } }, backgroundScope, clock, dispatcher, maxRows = maxRows)
    }

    private suspend fun AgendaRepository.settled(request: AgendaRequest) =
        observe(request).first { s -> !s.refreshing && s.calendars.values.all { it.events != null || it.error != null } }

    private fun reports() = server.count(Endpoint.CALDAV_REPORT)

    @Test
    fun aViewedRangeIsFetchedOnceThenServedFromTheCache() =
        runTest {
            val repo = repository()
            val first = repo.settled(request())
            assertEquals(
                listOf("a"),
                first.calendars
                    .getValue(personal)
                    .events
                    ?.map { it.summary },
            )
            assertEquals(
                listOf("w"),
                first.calendars
                    .getValue(work)
                    .events
                    ?.map { it.summary },
            )
            assertEquals(now, first.calendars.getValue(personal).fetchedAt)
            assertEquals(2, reports())

            // Reopened within 15 minutes: no request.
            now += 10.minutes
            repo.settled(request())
            assertEquals(2, reports())

            // Stale: shown at once, then refreshed.
            now += 10.minutes
            server.addCalendar("personal", "Personal", events = listOf(event("a2", 16)))
            val refreshed =
                repo.observe(request()).first {
                    it.calendars
                        .getValue(personal)
                        .events
                        ?.map { e -> e.summary } == listOf("a2")
                }
            assertEquals(4, reports())
            assertEquals(now, refreshed.calendars.getValue(personal).fetchedAt)

            // The Refresh action always fetches.
            repo.refresh(request())
            repo.settled(request())
            assertEquals(6, reports())
        }

    @Test
    fun onlyTheRangesAskedForAreFetched() =
        runTest {
            val repo = repository()
            repo.settled(request(calendars = listOf(work)))
            assertEquals(1, reports())
            assertEquals(1L, db.agendaQueries.countRows().executeAsOne())
        }

    @Test
    fun aFailureKeepsTheCachedEvents() =
        runTest {
            val repo = repository()
            repo.settled(request())
            now += 20.minutes
            server.offline = true
            val offline = repo.observe(request()).first { s -> !s.refreshing && s.calendars.values.all { it.error != null } }
            assertEquals(
                listOf("a"),
                offline.calendars
                    .getValue(personal)
                    .events
                    ?.map { it.summary },
            )
            assertIs<ApiError.Network>(offline.calendars.getValue(personal).error)

            // Never viewed while offline: nothing to show, and the error.
            val never = repo.settled(request(day = 17))
            assertNull(never.calendars.getValue(personal).events)
            assertIs<ApiError.Network>(never.calendars.getValue(personal).error)

            server.offline = false
            repo.refresh(request())
            val back = repo.observe(request()).first { s -> s.calendars.values.all { it.error == null } && !s.refreshing }
            assertEquals(
                listOf("a"),
                back.calendars
                    .getValue(personal)
                    .events
                    ?.map { it.summary },
            )
        }

    @Test
    fun noAccountNoRequest() =
        runTest {
            connected = false
            val s = repository().settled(request())
            assertEquals(ApiError.DavNotReady, s.calendars.getValue(personal).error)
            assertEquals(0, server.requests.size)
        }

    @Test
    fun theCacheIsBounded() =
        runTest {
            val repo = repository(maxRows = 3)
            repo.settled(request(day = 16))
            now += 1.minutes
            repo.settled(request(day = 17))
            assertEquals(3L, db.agendaQueries.countRows().executeAsOne())
            // The most recently viewed rows stay.
            assertEquals(
                2,
                db.agendaQueries
                    .selectRange("2026-11-17", 1, "Europe/Paris", listOf(personal, work))
                    .executeAsList()
                    .size,
            )

            // Rows not viewed for 30 days go on the next write.
            now += 31.days
            repo.settled(request(day = 18, calendars = listOf(work)))
            assertEquals(1L, db.agendaQueries.countRows().executeAsOne())
        }

    @Test
    fun forgetAndSignOutDropTheRows() =
        runTest {
            val repo = repository()
            repo.settled(request())
            repo.forget(setOf(work))
            assertEquals(1L, db.agendaQueries.countRows().executeAsOne())
            wipeLocalData(db)
            assertEquals(0L, db.agendaQueries.countRows().executeAsOne())
        }
}
