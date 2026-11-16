package app.citti.core.dav

import app.citti.core.agenda.CalEvent
import app.citti.core.agenda.ICal
import app.citti.core.api.ApiError
import app.citti.core.api.ApiResult
import app.citti.core.api.RequestStats
import app.citti.core.log.SyncLog
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLParserException
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.decodeURLPart
import io.ktor.http.protocolWithAuthority
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.readByteArray
import kotlin.time.Instant
import kotlin.time.TimeSource

/** An Event calendar as the server describes it. [href] is its path on the Account's server. */
data class DiscoveredCalendar(
    val href: String,
    val name: String,
    /** `#rrggbb`, or empty. */
    val color: String,
)

/** The largest multistatus read (a busy calendar over a month is far below). */
const val CALDAV_MAX_BYTES = 4 shl 20

private const val DAV = "DAV:"
private const val CALDAV = "urn:ietf:params:xml:ns:caldav"
private const val APPLE = "http://apple.com/ns/ical/"

/**
 * Read-only CalDAV access to the Account's calendars, `<baseUrl>/remote.php/dav/calendars/<userId>/`, over the
 * app's one HTTP client (ADR 0012). That client sends Basic auth to every URL, so this class only ever requests
 * URLs on the Account's host under that calendar home: a server href anywhere else is dropped. Nothing outside
 * this class builds a calendars URL. Outcomes are values, never exceptions.
 */
class CalDavClient(
    private val client: HttpClient,
    private val root: suspend () -> DavRoot?,
    private val log: SyncLog = SyncLog.None,
    private val stats: RequestStats? = null,
) {
    /** One `PROPFIND` (Depth 1) of the calendar home: the calendars that hold events. */
    suspend fun calendars(): ApiResult<List<DiscoveredCalendar>> {
        val root = root()?.takeIf { it.isValid } ?: return ApiResult.Failure(ApiError.DavNotReady)
        val body =
            """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="$DAV" xmlns:c="$CALDAV" xmlns:a="$APPLE">
  <d:prop><d:resourcetype/><d:displayname/><a:calendar-color/><c:supported-calendar-component-set/></d:prop>
</d:propfind>"""
        return multistatus("PROPFIND", homeUrl(root), body) { responses ->
            responses.mapNotNull { r ->
                val type = r.props[DAV to "resourcetype"] ?: return@mapNotNull null
                if (type.child(CALDAV, "calendar") == null) return@mapNotNull null
                val comps = r.props[CALDAV to "supported-calendar-component-set"]
                if (comps != null && comps.children(CALDAV, "comp").none { it.attributes["name"] == "VEVENT" }) return@mapNotNull null
                val path = calendarPath(root, r.href) ?: return@mapNotNull null
                val name =
                    r.props[DAV to "displayname"]
                        ?.text
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: path.trimEnd('/').substringAfterLast('/').decodeURLPart()
                val color =
                    r.props[APPLE to "calendar-color"]
                        ?.text
                        ?.trim()
                        .orEmpty()
                        .take(7)
                        .takeIf { HEX.matches(it) }
                        .orEmpty()
                DiscoveredCalendar(path, name, color)
            }
        }
    }

    /** One `REPORT` calendar-query: the events of [href] overlapping [from, to), recurring ones expanded by the server. */
    suspend fun events(
        href: String,
        from: Instant,
        to: Instant,
    ): ApiResult<List<CalEvent>> {
        val root = root()?.takeIf { it.isValid } ?: return ApiResult.Failure(ApiError.DavNotReady)
        val path = calendarPath(root, href) ?: return ApiResult.Failure(ApiError.NotFound)
        val s = utc(from)
        val e = utc(to)
        val body =
            """<?xml version="1.0" encoding="utf-8"?>
<c:calendar-query xmlns:d="$DAV" xmlns:c="$CALDAV">
  <d:prop><c:calendar-data><c:expand start="$s" end="$e"/></c:calendar-data></d:prop>
  <c:filter>
    <c:comp-filter name="VCALENDAR">
      <c:comp-filter name="VEVENT"><c:time-range start="$s" end="$e"/></c:comp-filter>
    </c:comp-filter>
  </c:filter>
</c:calendar-query>"""
        return multistatus("REPORT", origin(root) + path, body) { responses ->
            responses.flatMap { r ->
                r.props[CALDAV to "calendar-data"]
                    ?.text
                    ?.let(ICal::events)
                    .orEmpty()
            }
        }
    }

    internal fun homeUrl(root: DavRoot): String = origin(root) + homePath(root)

    private fun homePath(root: DavRoot): String =
        (Url(root.baseUrl).encodedPath.trimEnd('/') + "/remote.php/dav/calendars/" + encodeSegment(root.userId) + "/")

    private fun origin(root: DavRoot): String = Url(root.baseUrl).protocolWithAuthority

    /**
     * The path of the calendar at [href], or null when [href] is not one level under the Account's calendar
     * home on the Account's host: following it would send the app password elsewhere.
     */
    internal fun calendarPath(
        root: DavRoot,
        href: String,
    ): String? {
        val url =
            try {
                if (href.startsWith("/")) Url(origin(root) + href) else Url(href)
            } catch (_: URLParserException) {
                return null
            }
        if (url.protocolWithAuthority != origin(root)) return null
        val path = url.encodedPath
        val home = homePath(root)
        val decoded = path.decodeURLPart()
        if (!decoded.startsWith(home.decodeURLPart())) return null
        val rest = decoded.removePrefix(home.decodeURLPart()).trimEnd('/')
        if (rest.isEmpty() || '/' in rest || rest == "." || rest == "..") return null
        return if (path.endsWith("/")) path else "$path/"
    }

    private class DavResponse(
        val href: String,
        /** The properties of the 2xx propstats. */
        val props: Map<Pair<String, String>, XmlElement>,
    )

    private suspend fun <T> multistatus(
        verb: String,
        url: String,
        body: String,
        read: (List<DavResponse>) -> T,
    ): ApiResult<T> {
        val start = TimeSource.Monotonic.markNow()
        return try {
            val response =
                client.request(url) {
                    method = HttpMethod(verb)
                    header("Depth", "1")
                    setBody(TextContent(body, ContentType.parse("application/xml; charset=utf-8")))
                }
            stats?.record(response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L)
            log.info("req $verb caldav -> ${response.status.value} ${start.elapsedNow().inWholeMilliseconds}ms")
            when (val code = response.status.value) {
                MULTISTATUS -> {
                    val text = readCapped(response) ?: return failure(TOO_LARGE)
                    try {
                        ApiResult.Success(read(parse(text)))
                    } catch (e: XmlException) {
                        ApiResult.Failure(ApiError.BadResponse(e.message))
                    }
                }

                else -> {
                    failure(code)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            stats?.record(0L)
            log.warn("req $verb caldav failed: ${e::class.simpleName}")
            ApiResult.Failure(ApiError.Network(e))
        }
    }

    private fun parse(text: String): List<DavResponse> =
        Xml.parse(text).children(DAV, "response").map { r ->
            val props =
                r
                    .children(DAV, "propstat")
                    .filter { ps -> ps.child(DAV, "status")?.text?.let { STATUS_2XX.containsMatchIn(it) } ?: true }
                    .flatMap { ps -> ps.child(DAV, "prop")?.children.orEmpty() }
                    .associateBy { it.ns to it.name }
            DavResponse(
                r
                    .child(DAV, "href")
                    ?.text
                    ?.trim()
                    .orEmpty(),
                props,
            )
        }

    private suspend fun readCapped(response: HttpResponse): String? {
        val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > CALDAV_MAX_BYTES) return null
        val bytes = response.bodyAsChannel().readRemaining(CALDAV_MAX_BYTES + 1L).readByteArray()
        return bytes.takeIf { it.size <= CALDAV_MAX_BYTES }?.decodeToString()
    }

    private fun failure(code: Int): ApiResult.Failure =
        ApiResult.Failure(
            when (code) {
                401, 403 -> ApiError.Auth(code)
                404 -> ApiError.NotFound
                in 500..599 -> ApiError.Server(code)
                else -> ApiError.Http(code)
            },
        )

    private companion object {
        const val MULTISTATUS = 207
        const val TOO_LARGE = 413
        val STATUS_2XX = Regex("""\s2\d\d(\s|$)""")
        val HEX = Regex("^#[0-9a-fA-F]{6}$")

        /** iCalendar UTC time, e.g. `20261116T000000Z`. */
        fun utc(i: Instant): String {
            val t = i.toLocalDateTime(TimeZone.UTC)

            fun p(
                n: Int,
                w: Int = 2,
            ) = n.toString().padStart(w, '0')
            return "${p(t.year, 4)}${p(t.month.ordinal + 1)}${p(t.day)}T${p(t.hour)}${p(t.minute)}${p(t.second)}Z"
        }
    }
}
