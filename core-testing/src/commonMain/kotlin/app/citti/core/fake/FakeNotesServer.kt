package app.citti.core.fake

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent.ByteArrayContent
import io.ktor.http.content.TextContent
import io.ktor.http.parseUrlEncodedParameters
import io.ktor.http.protocolWithAuthority
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Header name style: HTTP/2 through a proxy is lowercase, HTTP/1.1 is canonical. */
enum class HeaderCasing { LOWERCASE, CANONICAL }

/** A failure injected with [FakeNotesServer.failNext]; consumed by the next request(s). */
enum class FakeFailure { NETWORK_ERROR, SERVER_ERROR, UNAUTHORIZED }

enum class Endpoint {
    LIST,
    GET_NOTE,
    CREATE,
    UPDATE,
    DELETE,
    SETTINGS,
    CAPABILITIES,
    CLOUD_USER,
    DAV_GET,

    /** `POST /index.php/login/v2`: starts Login Flow v2. */
    LOGIN_START,

    /** `POST /index.php/login/v2/poll`: 404 until the login is approved. */
    LOGIN_POLL,
    UNKNOWN,
}

/** Base URL of the fake in the E2E tests. It never resolves, so a request that misses the fake engine fails. */
const val E2E_SERVER = "https://cloud.example.test"

data class RecordedRequest(
    val method: HttpMethod,
    /** Full path as requested, including the base path prefix. */
    val path: String,
    val query: Map<String, String>,
    /** Header names as sent by the client; use [header] for case-insensitive lookup. */
    val headers: Map<String, List<String>>,
    val endpoint: Endpoint,
    /** Status returned, or null when a network error was injected or the server is [FakeNotesServer.offline]. */
    val status: Int?,
    /** The text body (JSON or form), empty when there is none. */
    val body: String = "",
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
 *
 * Thread safe: the app calls it from IO threads while the test thread changes it. Every public method and the
 * request handler run under one lock; [beforeDispatch] runs outside it.
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
    private val lock = SynchronizedObject()

    private inline fun <T> locked(block: () -> T): T = synchronized(lock, block)

    private val noteMap = LinkedHashMap<Long, FakeNote>()
    private var nextId = 1L
    private val failures = ArrayDeque<Pair<FakeFailure, Endpoint?>>()
    private val log = mutableListOf<RecordedRequest>()

    /** Bumped on every recorded request; [awaitRequest] waits on it. */
    private val logSize = MutableStateFlow(0)

    /**
     * When true, every request (Notes API, DAV, login) throws `IOException("offline")`. It is still recorded,
     * with `status = null`.
     */
    @Volatile
    var offline: Boolean = false

    private var login: LoginState = LoginState.Pending

    /** False: the capabilities have no `notes` entry, as on a server without the Notes app. */
    @Volatile
    var notesAppInstalled: Boolean = true

    /**
     * Called before a request is answered (after auth and injected failures), so a test can change the local DB
     * while the request is in flight.
     */
    var beforeDispatch: ((Endpoint) -> Unit)? = null

    var notesPath: String = "Notes"

    /** The Nextcloud user id (`cloud/user`); WebDAV files live under it. May differ from the login name. */
    var userId: String = "alice"
    private val files = LinkedHashMap<String, Pair<ByteArray, Int>>()

    /** Puts a file in the Vault at [vaultPath] (`media/a b.png`); the etag changes on every put. */
    fun putFile(
        vaultPath: String,
        bytes: ByteArray,
    ) = locked {
        val key = "$notesPath/$vaultPath"
        files[key] = bytes to ((files[key]?.second ?: 0) + 1)
    }

    fun removeFile(vaultPath: String) {
        locked { files.remove("$notesPath/$vaultPath") }
    }

    fun fileEtag(vaultPath: String): String? = locked { files["$notesPath/$vaultPath"]?.let { hash("${it.first.size}/${it.second}") } }

    var fileSuffix: String = ".md"

    val engine: MockEngine = MockEngine { request -> handle(request) }

    val requests: List<RecordedRequest> get() = locked { log.toList() }
    val notes: List<FakeNote> get() = locked { noteMap.values.toList() }

    fun count(endpoint: Endpoint): Int = locked { log.count { it.endpoint == endpoint } }

    fun clearRequests() = locked { log.clear() }

    fun advance(seconds: Long) {
        locked { now += seconds }
    }

    fun note(id: Long): FakeNote? = locked { noteMap[id] }

    /**
     * Suspends until a request to [endpoint] matching [predicate] is in the log (it may already be there), and
     * returns it. Throws `TimeoutCancellationException` after [timeoutMillis].
     */
    suspend fun awaitRequest(
        endpoint: Endpoint,
        timeoutMillis: Long = 5_000,
        predicate: (RecordedRequest) -> Boolean = { true },
    ): RecordedRequest {
        fun find() = locked { log.firstOrNull { it.endpoint == endpoint && predicate(it) } }
        return withTimeout(timeoutMillis) {
            logSize.first { find() != null }
            find()!!
        }
    }

    /**
     * Approves the pending Login Flow v2: the poll answers 404 [pollsBeforeSuccess] more times, then returns
     * [loginName] and [appPassword], with [server] (default: the URL the app called). The pair becomes the
     * accepted [credentials].
     */
    fun approveLogin(
        loginName: String,
        appPassword: String,
        pollsBeforeSuccess: Int = 0,
        server: String? = null,
    ) = locked {
        login = LoginState.Approved(loginName, appPassword, pollsBeforeSuccess, server)
        credentials = loginName to appPassword
    }

    /** The poll answers 403 from now on, as an expired or refused login token does. */
    fun denyLogin() = locked { login = LoginState.Denied }

    // ---- test-side state ----

    fun addNote(
        title: String,
        content: String = "",
        category: String = "",
        favorite: Boolean = false,
        modified: Long = now,
        readonly: Boolean = false,
        /** A fixed server id (fixtures); default: the next free one. */
        fixedId: Long? = null,
    ): Long =
        locked {
            require(fixedId == null || fixedId !in noteMap) { "note id $fixedId is taken" }
            val id = fixedId ?: nextId
            nextId = maxOf(nextId, id + 1)
            noteMap[id] = FakeNote(id, title, content, category, favorite, modified, 1, readonly)
            id
        }

    /** Edits a note as another client would: bumps etag and modified (to [modified], default now). */
    fun editOnServer(
        id: Long,
        content: String? = null,
        title: String? = null,
        category: String? = null,
        modified: Long = now,
    ) = locked {
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
        locked { noteMap.remove(id) }
    }

    fun setFavorite(
        id: Long,
        favorite: Boolean,
    ) = locked {
        val n = noteMap.getValue(id)
        noteMap[id] = n.copy(favorite = favorite, modified = now, version = n.version + 1)
    }

    fun clearFailures() = locked { failures.clear() }

    /**
     * Queues [times] failures, applied to the next requests in order. With an [endpoint], only requests to
     * that endpoint consume them (other requests pass through untouched).
     */
    fun failNext(
        failure: FakeFailure,
        times: Int = 1,
        endpoint: Endpoint? = null,
    ) = locked {
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
        locked {
            generated(count, topLevels)
        }

    private fun generated(
        count: Int,
        topLevels: List<String>,
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
        val body = bodyText(request)

        fun record(status: Int?) {
            locked {
                log += RecordedRequest(request.method, path, query, headers, endpoint, status, body)
                logSize.value += 1
            }
        }

        // Offline and injected failures are decided under the lock; beforeDispatch runs outside it.
        val early: HttpResponseData? =
            locked {
                if (offline) throw recordedIOException("offline", ::record)
                val queued = failures.indexOfFirst { it.second == null || it.second == endpoint }
                when (if (queued >= 0) failures.removeAt(queued).first else null) {
                    FakeFailure.NETWORK_ERROR -> throw recordedIOException("fake network error", ::record)
                    FakeFailure.UNAUTHORIZED -> text(HttpStatusCode.Unauthorized, "unauthorized")
                    FakeFailure.SERVER_ERROR -> text(HttpStatusCode.InternalServerError, "boom")
                    null -> if (authorized(request, endpoint)) null else text(HttpStatusCode.Unauthorized, "unauthorized")
                }
            }
        val response =
            early ?: run {
                beforeDispatch?.invoke(endpoint)
                locked { dispatch(request, endpoint, path, query, body) }
            }
        record(response.statusCode.value)
        return response
    }

    private fun recordedIOException(
        message: String,
        record: (Int?) -> Unit,
    ): IOException {
        record(null)
        return IOException(message)
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

            rest == CLOUD_USER -> {
                Endpoint.CLOUD_USER
            }

            rest == LOGIN_V2 && method == HttpMethod.Post -> {
                Endpoint.LOGIN_START
            }

            rest == "$LOGIN_V2/poll" && method == HttpMethod.Post -> {
                Endpoint.LOGIN_POLL
            }

            rest.startsWith(DAV_FILES) && method == HttpMethod.Get -> {
                Endpoint.DAV_GET
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
    private fun authorized(
        request: HttpRequestData,
        endpoint: Endpoint,
    ): Boolean {
        // The login flow carries no credentials, like on the real server.
        if (endpoint == Endpoint.LOGIN_START || endpoint == Endpoint.LOGIN_POLL) return true
        val (user, password) = credentials ?: return true
        val expected = "Basic " + Base64.encode("$user:$password".encodeToByteArray())
        return request.headers["Authorization"] == expected
    }

    private fun MockRequestHandleScope.dispatch(
        request: HttpRequestData,
        endpoint: Endpoint,
        path: String,
        query: Map<String, String>,
        body: String,
    ): HttpResponseData =
        when (endpoint) {
            Endpoint.LIST -> {
                list(request, query)
            }

            Endpoint.CREATE -> {
                val body = bodyJson(body)
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
                        val body = bodyJson(body)
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
                val notes = if (notesAppInstalled) """"notes":{"api_version":["0.2","1.3","1.4"],"version":"6.1.0"}""" else ""
                json(
                    """{"ocs":{"meta":{"status":"ok","statuscode":200,"message":"OK"},""" +
                        """"data":{"version":{"major":33,"string":"33.0.0"},""" +
                        """"capabilities":{$notes}}}}""",
                )
            }

            Endpoint.CLOUD_USER -> {
                json("""{"ocs":{"meta":{"status":"ok","statuscode":200},"data":{"id":"$userId"}}}""")
            }

            Endpoint.DAV_GET -> {
                davGet(request, path)
            }

            Endpoint.LOGIN_START -> {
                val origin = request.url.protocolWithAuthority + basePath
                login = LoginState.Pending
                json(
                    buildJsonObject {
                        put(
                            "poll",
                            buildJsonObject {
                                put("token", LOGIN_TOKEN)
                                put("endpoint", "$origin$LOGIN_V2/poll")
                            },
                        )
                        put("login", "$origin$LOGIN_V2/flow/$LOGIN_TOKEN")
                    }.toString(),
                )
            }

            Endpoint.LOGIN_POLL -> {
                loginPoll(request, body)
            }

            Endpoint.UNKNOWN -> {
                text(HttpStatusCode.NotFound, "not found")
            }
        }

    private fun MockRequestHandleScope.loginPoll(
        request: HttpRequestData,
        body: String,
    ): HttpResponseData {
        val token = body.parseUrlEncodedParameters()["token"]
        if (token != LOGIN_TOKEN) return text(HttpStatusCode.NotFound, "unknown token")
        return when (val state = login) {
            LoginState.Pending -> {
                text(HttpStatusCode.NotFound, "not yet")
            }

            LoginState.Denied -> {
                text(HttpStatusCode.Forbidden, "denied")
            }

            is LoginState.Approved -> {
                if (state.pollsLeft > 0) {
                    login = state.copy(pollsLeft = state.pollsLeft - 1)
                    text(HttpStatusCode.NotFound, "not yet")
                } else {
                    // One use, like the real token.
                    login = LoginState.Pending
                    json(
                        buildJsonObject {
                            put("server", state.server ?: (request.url.protocolWithAuthority + basePath))
                            put("loginName", state.loginName)
                            put("appPassword", state.appPassword)
                        }.toString(),
                    )
                }
            }
        }
    }

    private sealed interface LoginState {
        data object Pending : LoginState

        data object Denied : LoginState

        data class Approved(
            val loginName: String,
            val appPassword: String,
            val pollsLeft: Int,
            val server: String?,
        ) : LoginState
    }

    private fun MockRequestHandleScope.davGet(
        request: HttpRequestData,
        path: String,
    ): HttpResponseData {
        val rest = path.removePrefix(basePath).removePrefix(DAV_FILES)
        val user = percentDecodeForFake(rest.substringBefore('/'))
        if (user != userId) return text(HttpStatusCode.NotFound, "no such user")
        val key = percentDecodeForFake(rest.substringAfter('/', ""))
        val (bytes, version) = files[key] ?: return text(HttpStatusCode.NotFound, "no such file")
        val etag = hash("${bytes.size}/$version")
        val headers = HeadersBuilder()
        headers.name("ETag", etag)
        if (request.headers["If-None-Match"] == etag) return respond(ByteArray(0), HttpStatusCode.NotModified, headers.build())
        headers.name("Content-Type", "application/octet-stream")
        return respond(bytes, HttpStatusCode.OK, headers.build())
    }

    private fun percentDecodeForFake(s: String): String {
        val out = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '%') {
                out += s.substring(i + 1, i + 3).toInt(16).toByte()
                i += 3
            } else {
                out += s[i].toString().encodeToByteArray().toList()
                i++
            }
        }
        return out.toByteArray().decodeToString()
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

    private fun bodyText(request: HttpRequestData): String =
        when (val body = request.body) {
            is TextContent -> body.text
            is ByteArrayContent -> body.bytes().decodeToString()
            else -> ""
        }

    private fun bodyJson(text: String): JsonObject =
        runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

    private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull

    private companion object {
        const val NOTES_API = "/index.php/apps/notes/api/v1"
        const val CAPABILITIES = "/ocs/v2.php/cloud/capabilities"
        const val CLOUD_USER = "/ocs/v2.php/cloud/user"
        const val DAV_FILES = "/remote.php/dav/files/"
        const val LOGIN_V2 = "/index.php/login/v2"
        const val LOGIN_TOKEN = "fake-login-token"
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
fun httpDate(epochSeconds: Long): String {
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
