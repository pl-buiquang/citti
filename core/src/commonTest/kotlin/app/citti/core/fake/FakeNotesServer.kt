package app.citti.core.fake

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Header name style: HTTP/2 through a proxy is lowercase, HTTP/1.1 is canonical. */
enum class HeaderCasing { LOWERCASE, CANONICAL }

/** A failure injected with [FakeNotesServer.failNext]; consumed by the next request(s). */
enum class FakeFailure { NETWORK_ERROR, SERVER_ERROR, UNAUTHORIZED }

enum class Endpoint { LIST, GET_NOTE, CREATE, UPDATE, DELETE, SETTINGS, CAPABILITIES, UNKNOWN }

data class RecordedRequest(
    val method: HttpMethod,
    /** Full path as requested, including the base path prefix. */
    val path: String,
    val query: Map<String, String>,
    /** Header names as sent by the client; use [header] for case-insensitive lookup. */
    val headers: Map<String, List<String>>,
    val endpoint: Endpoint,
    /** Status returned, or null when a network error was injected. */
    val status: Int?,
) {
    fun header(name: String): String? =
        headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
}

data class FakeNote(
    val id: Long,
    val title: String,
    val content: String,
    val category: String,
    val favorite: Boolean,
    /** Epoch seconds. */
    val modified: Long,
    /** Bumped on every write; the etag is derived from it. */
    val version: Int,
    val readonly: Boolean = false,
) {
    val etag: String get() = hash("$id/$version/$modified").removeSurrounding("\"")
}

/**
 * Stateful fake of the Nextcloud Notes API v1 on a Ktor [MockEngine], behaving like the real server
 * (see the "Server probe" section of the M2 plan). Independent of the app's own API client.
 *
 * All times are epoch seconds from [now]; nothing reads the real clock.
 */
class FakeNotesServer(
    /** Path prefix of the Nextcloud install, for example `/cloud`. Empty when served at the root. */
    val basePath: String = "",
    var headerCasing: HeaderCasing = HeaderCasing.CANONICAL,
    /** When set, requests without the matching Basic credentials get 401. */
    var credentials: Pair<String, String>? = null,
    /** Current server time in epoch seconds; set it or call [advance] to move time. */
    var now: Long = 1_800_000_000L,
) {
    private val noteMap = LinkedHashMap<Long, FakeNote>()
    private var nextId = 1L
    private val failures = ArrayDeque<Pair<FakeFailure, Endpoint?>>()
    private val log = mutableListOf<RecordedRequest>()

    /**
     * Called before a request is answered (after auth and injected failures), so a test can change the local DB
     * while the request is in flight.
     */
    var beforeDispatch: ((Endpoint) -> Unit)? = null

    var notesPath: String = "Notes"
    var fileSuffix: String = ".md"

    val engine: MockEngine = MockEngine { request -> handle(request) }

    val requests: List<RecordedRequest> get() = log.toList()
    val notes: List<FakeNote> get() = noteMap.values.toList()

    fun count(endpoint: Endpoint): Int = log.count { it.endpoint == endpoint }

    fun clearRequests() = log.clear()

    fun advance(seconds: Long) {
        now += seconds
    }

    fun note(id: Long): FakeNote? = noteMap[id]

    // ---- test-side state ----

    fun addNote(
        title: String,
        content: String = "",
        category: String = "",
        favorite: Boolean = false,
        modified: Long = now,
        readonly: Boolean = false,
    ): Long {
        val id = nextId++
        noteMap[id] = FakeNote(id, title, content, category, favorite, modified, 1, readonly)
        return id
    }

    /** Edits a note as another client would: bumps etag and modified (to [modified], default now). */
    fun editOnServer(
        id: Long,
        content: String? = null,
        title: String? = null,
        category: String? = null,
        modified: Long = now,
    ) {
        val n = noteMap.getValue(id)
        noteMap[id] =
            n.copy(
                content = content ?: n.content,
                title = title ?: n.title,
                category = category ?: n.category,
                modified = modified,
                version = n.version + 1,
            )
    }

    fun deleteOnServer(id: Long) {
        noteMap.remove(id)
    }

    fun setFavorite(
        id: Long,
        favorite: Boolean,
    ) {
        val n = noteMap.getValue(id)
        noteMap[id] = n.copy(favorite = favorite, modified = now, version = n.version + 1)
    }

    fun clearFailures() = failures.clear()

    /**
     * Queues [times] failures, applied to the next requests in order. With an [endpoint], only requests to
     * that endpoint consume them (other requests pass through untouched).
     */
    fun failNext(
        failure: FakeFailure,
        times: Int = 1,
        endpoint: Endpoint? = null,
    ) {
        repeat(times) { failures.addLast(failure to endpoint) }
    }

    /**
     * Adds [count] deterministic notes spread over nested categories (`_work/p3/s1`, ...),
     * with distinct modification times going back from now. Returns the ids.
     */
    fun generateNotes(
        count: Int = 2300,
        topLevels: List<String> = listOf("_work", "journal", "archives", "projects", ""),
    ): List<Long> =
        List(count) { i ->
            val top = topLevels[i % topLevels.size]
            val category =
                when {
                    top.isEmpty() -> ""
                    i % 3 == 0 -> top
                    i % 3 == 1 -> "$top/p${i % 17}"
                    else -> "$top/p${i % 17}/s${i % 5}"
                }
            addNote("Note $i", "Content of note $i", category, favorite = i % 700 == 0, modified = now - i - 1)
        }

    // ---- request handling ----

    private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        val endpoint = route(request.method, path)
        val query =
            request.url.parameters
                .entries()
                .associate { it.key to it.value.first() }
        val headers = request.headers.entries().associate { it.key to it.value }

        fun record(status: Int?) {
            log += RecordedRequest(request.method, path, query, headers, endpoint, status)
        }

        val queued = failures.indexOfFirst { it.second == null || it.second == endpoint }
        val failure = if (queued >= 0) failures.removeAt(queued).first else null
        if (failure == FakeFailure.NETWORK_ERROR) {
            record(null)
            throw IOException("fake network error")
        }
        val response =
            when {
                failure == FakeFailure.UNAUTHORIZED || !authorized(request) -> {
                    text(HttpStatusCode.Unauthorized, "unauthorized")
                }

                failure == FakeFailure.SERVER_ERROR -> {
                    text(HttpStatusCode.InternalServerError, "boom")
                }

                else -> {
                    beforeDispatch?.invoke(endpoint)
                    dispatch(request, endpoint, path, query)
                }
            }
        record(response.statusCode.value)
        return response
    }

    private fun route(
        method: HttpMethod,
        path: String,
    ): Endpoint {
        if (!path.startsWith(basePath)) return Endpoint.UNKNOWN
        val rest = path.removePrefix(basePath)
        val noteId = rest.removePrefix("$NOTES_API/notes/").toLongOrNull()
        return when {
            rest == "$NOTES_API/notes" -> {
                when (method) {
                    HttpMethod.Get -> Endpoint.LIST
                    HttpMethod.Post -> Endpoint.CREATE
                    else -> Endpoint.UNKNOWN
                }
            }

            rest == "$NOTES_API/settings" -> {
                Endpoint.SETTINGS
            }

            rest == CAPABILITIES -> {
                Endpoint.CAPABILITIES
            }

            noteId != null -> {
                when (method) {
                    HttpMethod.Get -> Endpoint.GET_NOTE
                    HttpMethod.Put -> Endpoint.UPDATE
                    HttpMethod.Delete -> Endpoint.DELETE
                    else -> Endpoint.UNKNOWN
                }
            }

            else -> {
                Endpoint.UNKNOWN
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun authorized(request: HttpRequestData): Boolean {
        val (user, password) = credentials ?: return true
        val expected = "Basic " + Base64.encode("$user:$password".encodeToByteArray())
        return request.headers["Authorization"] == expected
    }

    private fun MockRequestHandleScope.dispatch(
        request: HttpRequestData,
        endpoint: Endpoint,
        path: String,
        query: Map<String, String>,
    ): HttpResponseData =
        when (endpoint) {
            Endpoint.LIST -> {
                list(request, query)
            }

            Endpoint.CREATE -> {
                val body = bodyJson(request)
                val category = body.str("category") ?: ""
                val id =
                    addNote(
                        title = freeTitle(category, body.str("title") ?: ""),
                        content = body.str("content") ?: "",
                        category = category,
                        favorite = body.bool("favorite") ?: false,
                    )
                val created = noteMap.getValue(id)
                json(noteJson(created, withContent = true).toString(), etagOf = created)
            }

            Endpoint.GET_NOTE -> {
                withNote(path) { json(noteJson(it, withContent = true).toString(), etagOf = it) }
            }

            Endpoint.UPDATE -> {
                withNote(path) { n ->
                    val ifMatch = request.headers["If-Match"]?.removeSurrounding("\"")
                    if (ifMatch != null && ifMatch != n.etag) {
                        text(HttpStatusCode.PreconditionFailed, "etag mismatch")
                    } else {
                        val body = bodyJson(request)
                        val updated =
                            n.copy(
                                title = body.str("title") ?: n.title,
                                content = body.str("content") ?: n.content,
                                category = body.str("category") ?: n.category,
                                favorite = body.bool("favorite") ?: n.favorite,
                                modified = now,
                                version = n.version + 1,
                            )
                        noteMap[n.id] = updated
                        json(noteJson(updated, withContent = true).toString(), etagOf = updated)
                    }
                }
            }

            Endpoint.DELETE -> {
                withNote(path) { n ->
                    val ifMatch = request.headers["If-Match"]?.removeSurrounding("\"")
                    if (ifMatch != null && ifMatch != n.etag) {
                        text(HttpStatusCode.PreconditionFailed, "etag mismatch")
                    } else {
                        noteMap.remove(n.id)
                        json("[]")
                    }
                }
            }

            Endpoint.SETTINGS -> {
                json(
                    buildJsonObject {
                        put("notesPath", notesPath)
                        put("fileSuffix", fileSuffix)
                    }.toString(),
                )
            }

            Endpoint.CAPABILITIES -> {
                json(
                    """{"ocs":{"meta":{"status":"ok","statuscode":200,"message":"OK"},""" +
                        """"data":{"version":{"major":33,"string":"33.0.0"},""" +
                        """"capabilities":{"notes":{"api_version":["0.2","1.3","1.4"],"version":"6.1.0"}}}}}""",
                )
            }

            Endpoint.UNKNOWN -> {
                text(HttpStatusCode.NotFound, "not found")
            }
        }

    /** Like Nextcloud, a title already used in the folder becomes `Title (2)`, `Title (3)`... */
    private fun freeTitle(
        category: String,
        title: String,
    ): String {
        fun taken(candidate: String) = noteMap.values.any { it.category == category && it.title == candidate }
        var candidate = title
        var n = 2
        while (taken(candidate)) candidate = "$title ($n)".also { n++ }
        return candidate
    }

    private fun MockRequestHandleScope.withNote(
        path: String,
        block: (FakeNote) -> HttpResponseData,
    ): HttpResponseData {
        val note = path.substringAfterLast('/').toLongOrNull()?.let { noteMap[it] }
        return if (note == null) text(HttpStatusCode.NotFound, "note not found") else block(note)
    }

    private fun MockRequestHandleScope.list(
        request: HttpRequestData,
        query: Map<String, String>,
    ): HttpResponseData {
        val excludeContent = query["exclude"]?.split(',')?.contains("content") == true
        val pruneBefore = query["pruneBefore"]?.toLongOrNull()
        val chunkSize = query["chunkSize"]?.toIntOrNull()
        val cursor = query["chunkCursor"]?.let(::parseCursor)

        // Newest first, ties by id; the cursor is a position in this order.
        val ordered = noteMap.values.sortedWith(compareByDescending<FakeNote> { it.modified }.thenByDescending { it.id })
        val seen = if (cursor == null) emptyList() else ordered.filter { it.isAtOrBefore(cursor) }
        val unseen = if (cursor == null) ordered else ordered.filterNot { it.isAtOrBefore(cursor) }
        val page = if (chunkSize == null) unseen else unseen.take(chunkSize)
        val pending = unseen.size - page.size

        val items =
            seen.map { buildJsonObject { put("id", it.id) } } +
                page.map { n ->
                    if (pruneBefore != null && n.modified <= pruneBefore) {
                        buildJsonObject { put("id", n.id) }
                    } else {
                        noteJson(n, withContent = !excludeContent)
                    }
                }
        val body = JsonArray(items).toString()

        val shape = query.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }
        val etag = hash("$shape|$body")
        val headers = HeadersBuilder()
        headers.name("ETag", etag)
        headers.name("Last-Modified", httpDate(now))

        // The real server answers a chunked list with 200 even for a matching ETag.
        if (chunkSize == null && request.headers["If-None-Match"]?.removePrefix("W/") == etag) {
            return respond("", HttpStatusCode.NotModified, headers.build())
        }
        if (pending > 0) {
            val last = page.last()
            headers.name("X-Notes-Chunk-Cursor", "$now-${last.modified}-${last.id}")
            headers.name("X-Notes-Chunk-Pending", pending.toString())
        }
        headers.append("Content-Type", "application/json")
        return respond(body, HttpStatusCode.OK, headers.build())
    }

    private fun FakeNote.isAtOrBefore(cursor: Cursor): Boolean =
        modified > cursor.modified || (modified == cursor.modified && id >= cursor.id)

    private class Cursor(
        val modified: Long,
        val id: Long,
    )

    private fun parseCursor(raw: String): Cursor? {
        val parts = raw.split('-')
        if (parts.size != 3) return null
        return Cursor(parts[1].toLongOrNull() ?: return null, parts[2].toLongOrNull() ?: return null)
    }

    private fun HeadersBuilder.name(
        canonical: String,
        value: String,
    ) = append(if (headerCasing == HeaderCasing.LOWERCASE) canonical.lowercase() else canonical, value)

    private fun MockRequestHandleScope.json(
        body: String,
        etagOf: FakeNote? = null,
    ): HttpResponseData {
        val headers = HeadersBuilder()
        headers.append("Content-Type", "application/json")
        if (etagOf != null) headers.name("ETag", "\"${etagOf.etag}\"")
        return respond(body, HttpStatusCode.OK, headers.build())
    }

    private fun MockRequestHandleScope.text(
        status: HttpStatusCode,
        body: String,
    ): HttpResponseData = respond(body, status)

    private fun noteJson(
        n: FakeNote,
        withContent: Boolean,
    ): JsonObject =
        buildJsonObject {
            put("id", n.id)
            put("etag", n.etag)
            put("readonly", n.readonly)
            if (withContent) put("content", n.content)
            put("title", n.title)
            put("category", n.category)
            put("favorite", n.favorite)
            put("modified", n.modified)
            put("internalPath", (if (n.category.isEmpty()) "" else n.category + "/") + n.title + fileSuffix)
            put("isShared", false)
            put("error", false)
            put("errorType", "")
        }

    private fun bodyJson(request: HttpRequestData): JsonObject {
        val text =
            when (val body = request.body) {
                is TextContent -> body.text
                is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
                else -> ""
            }
        return runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
    }

    private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull

    private companion object {
        const val NOTES_API = "/index.php/apps/notes/api/v1"
        const val CAPABILITIES = "/ocs/v2.php/cloud/capabilities"
    }
}

/** Deterministic quoted hash used for ETags. */
private fun hash(s: String): String {
    var h = 1125899906842597L
    for (c in s) h = 31 * h + c.code
    return "\"" + h.toULong().toString(16) + "\""
}

private val DAYS = listOf("Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed")
private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** RFC 1123 date for the `Last-Modified` header, without any platform date API. */
internal fun httpDate(epochSeconds: Long): String {
    val days = epochSeconds.floorDiv(86_400L)
    val secs = epochSeconds.mod(86_400L)
    // civil-from-days (Howard Hinnant)
    val z = days + 719_468
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + (if (m <= 2) 1 else 0)

    fun two(n: Long) = n.toString().padStart(2, '0')
    return "${DAYS[days.mod(7L).toInt()]}, ${two(d)} ${MONTHS[(m - 1).toInt()]} $y " +
        "${two(secs / 3600)}:${two(secs % 3600 / 60)}:${two(secs % 60)} GMT"
}
