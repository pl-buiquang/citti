package app.citti.core.dav

import app.citti.core.api.ApiError
import app.citti.core.api.ApiResult
import app.citti.core.api.NotesApiConfig
import app.citti.core.api.createNotesHttpClient
import app.citti.core.fake.Endpoint
import app.citti.core.fake.FakeFailure
import app.citti.core.fake.FakeNotesServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class CalDavClientTest {
    private val server = FakeNotesServer(basePath = "/cloud", credentials = "alice@example.com" to "s3cret").apply { userId = "alice" }
    private var root: DavRoot? = DavRoot("https://host/cloud/", "alice", "Notes")

    private fun client(): CalDavClient {
        val config = NotesApiConfig("https://host/cloud/", "alice@example.com", "s3cret")
        return CalDavClient(createNotesHttpClient(server.engine, config), { root })
    }

    private val standup =
        """
        BEGIN:VEVENT
        UID:standup
        DTSTART:20261116T090000Z
        DTEND:20261116T093000Z
        SUMMARY:Standup & coffee
        END:VEVENT
        """.trimIndent()

    private val tomorrow =
        """
        BEGIN:VEVENT
        UID:tomorrow
        DTSTART:20261117T090000Z
        DTEND:20261117T100000Z
        SUMMARY:Tomorrow
        END:VEVENT
        """.trimIndent()

    @Test
    fun discoversTheEventCalendarsOfTheAccount() =
        runTest {
            server.addCalendar("personal", "Personal", "#0082c9")
            server.addCalendar("work", "Work <3", "#FF0000")
            server.addCalendar("tasks", "Tasks", components = listOf("VTODO"))
            val result = assertIs<ApiResult.Success<List<DiscoveredCalendar>>>(client().calendars())
            assertEquals(
                listOf(
                    DiscoveredCalendar("/cloud/remote.php/dav/calendars/alice/personal/", "Personal", "#0082c9"),
                    DiscoveredCalendar("/cloud/remote.php/dav/calendars/alice/work/", "Work <3", "#FF0000"),
                ),
                result.value,
            )
            val request = server.requests.single()
            assertEquals(Endpoint.CALDAV_PROPFIND, request.endpoint)
            assertEquals("/cloud/remote.php/dav/calendars/alice/", request.path)
            assertEquals("1", request.header("depth"))
            assertTrue(request.header("content-type")!!.startsWith("application/xml"))
        }

    @Test
    fun queriesTheEventsOfOneCalendarInARange() =
        runTest {
            server.addCalendar("personal", "Personal", events = listOf(standup, tomorrow))
            val result =
                client().events(
                    server.calendarHref("personal"),
                    Instant.parse("2026-11-15T23:00:00Z"),
                    Instant.parse("2026-11-16T23:00:00Z"),
                )
            val events = assertIs<ApiResult.Success<List<app.citti.core.agenda.CalEvent>>>(result).value
            assertEquals(listOf("Standup & coffee"), events.map { it.summary })
            val request = server.requests.single()
            assertEquals(Endpoint.CALDAV_REPORT, request.endpoint)
            assertTrue("""<c:time-range start="20261115T230000Z" end="20261116T230000Z"/>""" in request.body)
            assertTrue("<c:expand" in request.body)
        }

    @Test
    fun failuresAreValues() =
        runTest {
            server.addCalendar("personal", "Personal")
            val href = server.calendarHref("personal")
            val from = Instant.parse("2026-11-16T00:00:00Z")
            val to = Instant.parse("2026-11-17T00:00:00Z")
            server.failNext(FakeFailure.UNAUTHORIZED)
            assertEquals(ApiResult.Failure(ApiError.Auth(401)), client().events(href, from, to))
            server.failNext(FakeFailure.SERVER_ERROR)
            assertEquals(ApiResult.Failure(ApiError.Server(500)), client().events(href, from, to))
            server.failNext(FakeFailure.NETWORK_ERROR)
            assertIs<ApiError.Network>((client().events(href, from, to) as ApiResult.Failure).error)
            assertEquals(ApiResult.Failure(ApiError.NotFound), client().events(server.calendarHref("gone"), from, to))
            root = null
            assertEquals(ApiResult.Failure(ApiError.DavNotReady), client().calendars())
            assertEquals(4, server.requests.size)
        }

    @Test
    fun neverFollowsAnHrefOffTheCalendarHome() =
        runTest {
            val c = client()
            val r = root!!
            assertEquals(
                "/cloud/remote.php/dav/calendars/alice/personal/",
                c.calendarPath(r, "/cloud/remote.php/dav/calendars/alice/personal"),
            )
            assertEquals(
                "/cloud/remote.php/dav/calendars/alice/personal/",
                c.calendarPath(r, "https://host/cloud/remote.php/dav/calendars/alice/personal/"),
            )
            listOf(
                "https://evil.example/cloud/remote.php/dav/calendars/alice/personal/",
                "http://host/cloud/remote.php/dav/calendars/alice/personal/",
                "https://host:8443/cloud/remote.php/dav/calendars/alice/personal/",
                "//evil.example/cloud/remote.php/dav/calendars/alice/personal/",
                "/cloud/remote.php/dav/calendars/bob/personal/",
                "/cloud/remote.php/dav/calendars/alice/",
                "/cloud/remote.php/dav/calendars/alice/../bob/",
                "/cloud/remote.php/dav/files/alice/Notes/",
                "/cloud/remote.php/dav/calendars/alice/personal/event.ics",
            ).forEach { assertNull(c.calendarPath(r, it), it) }
            assertEquals(
                ApiResult.Failure(ApiError.NotFound),
                c.events("https://evil.example/x/", Instant.parse("2026-11-16T00:00:00Z"), Instant.parse("2026-11-17T00:00:00Z")),
            )
            assertEquals(0, server.requests.size)
        }

    @Test
    fun anUnreadableOrHugeMultistatusIsABadResponse() =
        runTest {
            var body = "<d:multistatus xmlns:d=\"DAV:\"><d:response>"
            val engine = MockEngine { respond(body, HttpStatusCode.MultiStatus) }
            val c = CalDavClient(HttpClient(engine), { root })
            assertIs<ApiError.BadResponse>((c.calendars() as ApiResult.Failure).error)
            body = "<!DOCTYPE x><x/>"
            assertIs<ApiError.BadResponse>((c.calendars() as ApiResult.Failure).error)
            body = "x".repeat(CALDAV_MAX_BYTES + 1)
            assertEquals(ApiResult.Failure(ApiError.Http(413)), c.calendars())
        }
}
