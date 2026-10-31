package app.citti.core.dav

import app.citti.core.api.ApiError
import app.citti.core.api.ApiResult
import app.citti.core.api.RequestStats
import app.citti.core.log.SyncLog
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import kotlinx.coroutines.CancellationException
import kotlin.time.TimeSource

/**
 * Where the Vault is on the server: `<baseUrl>/remote.php/dav/files/<userId>/<notesPath>/`. Both values come from
 * the server, so a URL never leaves the Notes folder: `.`, `..` and empty segments of [notesPath] are dropped, and
 * [DavClient] makes no request for a [userId] that is not [valid][isValidUserId].
 */
data class DavRoot(
    val baseUrl: String,
    val userId: String,
    val notesPath: String,
) {
    val isValid: Boolean get() = isValidUserId(userId)

    internal fun url(path: VaultPath): String {
        val notes = notesPath.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        val segments = listOf("remote.php", "dav", "files", userId) + notes + path.value.split('/')
        return baseUrl.trimEnd('/') + "/" + segments.joinToString("/") { encodeSegment(it) }
    }

    companion object {
        /** A user id is one URL segment: not empty, no `/`, and not `.` or `..`. */
        fun isValidUserId(id: String): Boolean = id.isNotEmpty() && '/' !in id && id != "." && id != ".."
    }
}

sealed interface DavGet {
    class Body(
        val bytes: ByteArray,
        val etag: String?,
        val contentType: String?,
    ) : DavGet

    data object NotModified : DavGet
}

sealed interface DavPut {
    /** 201 (or 204 without a precondition). [etag] is the `OC-ETag` header, else `ETag`; the real server sends only `OC-ETag`. */
    data class Created(
        val etag: String?,
    ) : DavPut

    /** 412: `If-None-Match: *` and a file is already there; it is left unchanged. */
    data object Exists : DavPut
}

sealed interface DavMkcol {
    data object Created : DavMkcol

    /** 405: something is already at that path. */
    data object Exists : DavMkcol
}

/** Timeout of one `PUT`: camera photos run 3–8 MB. */
const val DAV_PUT_TIMEOUT_MS = 60_000L

private const val OC_ETAG = "OC-ETag"

/**
 * Authenticated WebDAV access to the Vault, over the app's one HTTP client (Basic auth and User-Agent are set
 * there). Outcomes are values, never exceptions. Nothing outside this class builds a WebDAV URL.
 */
class DavClient(
    private val client: HttpClient,
    private val root: suspend () -> DavRoot?,
    private val log: SyncLog = SyncLog.None,
    private val stats: RequestStats? = null,
) {
    /**
     * `GET` of [path]; with [ifNoneMatch] a 304 gives [DavGet.NotModified]. No request while the root is unknown or
     * not valid.
     */
    suspend fun get(
        path: VaultPath,
        ifNoneMatch: String? = null,
    ): ApiResult<DavGet> {
        val root = root()?.takeIf { it.isValid } ?: return ApiResult.Failure(ApiError.DavNotReady)
        return call("GET", { client.get(root.url(path)) { ifNoneMatch?.let { header(HttpHeaders.IfNoneMatch, it) } } }) { response ->
            when (val code = response.status.value) {
                HttpStatusCode.NotModified.value -> {
                    ApiResult.Success(DavGet.NotModified)
                }

                in 200..299 -> {
                    val etag = response.headers[HttpHeaders.ETag]
                    ApiResult.Success(DavGet.Body(response.body<ByteArray>(), etag, response.headers[HttpHeaders.ContentType]))
                }

                else -> {
                    failure(code)
                }
            }
        }
    }

    /**
     * `PUT` of [bytes] at [path]. With [ifNoneMatch] `*` an existing file gives [DavPut.Exists] and is not touched.
     * WebDAV creates no parent folder: on a 409 the missing levels are created with [mkcol] and the `PUT` is
     * retried once.
     */
    suspend fun put(
        path: VaultPath,
        bytes: ByteArray,
        contentType: String,
        ifNoneMatch: String? = "*",
    ): ApiResult<DavPut> {
        val root = root()?.takeIf { it.isValid } ?: return ApiResult.Failure(ApiError.DavNotReady)
        val first = putOnce(root, path, bytes, contentType, ifNoneMatch)
        if ((first as? ApiResult.Failure)?.error != ApiError.Http(HttpStatusCode.Conflict.value)) return first
        val parent = VaultPath.parse(path.folder) ?: return first
        return when (val made = mkcolTree(parent)) {
            is ApiResult.Failure -> made
            is ApiResult.Success -> putOnce(root, path, bytes, contentType, ifNoneMatch)
        }
    }

    /** `MKCOL` of one folder; its parent must exist (a 409 otherwise, as [ApiError.Http]). */
    suspend fun mkcol(path: VaultPath): ApiResult<DavMkcol> {
        val root = root()?.takeIf { it.isValid } ?: return ApiResult.Failure(ApiError.DavNotReady)
        return call("MKCOL", { client.request(root.url(path)) { method = HttpMethod("MKCOL") } }) { response ->
            when (val code = response.status.value) {
                in 200..299 -> ApiResult.Success(DavMkcol.Created)
                HttpStatusCode.MethodNotAllowed.value -> ApiResult.Success(DavMkcol.Exists)
                else -> failure(code)
            }
        }
    }

    /** Creates [folder], and first its missing parents when the server answers 409. */
    private suspend fun mkcolTree(folder: VaultPath): ApiResult<DavMkcol> {
        val first = mkcol(folder)
        if ((first as? ApiResult.Failure)?.error != ApiError.Http(HttpStatusCode.Conflict.value)) return first
        val parent = VaultPath.parse(folder.folder) ?: return first
        return when (val made = mkcolTree(parent)) {
            is ApiResult.Failure -> made
            is ApiResult.Success -> mkcol(folder)
        }
    }

    private suspend fun putOnce(
        root: DavRoot,
        path: VaultPath,
        bytes: ByteArray,
        contentType: String,
        ifNoneMatch: String?,
    ): ApiResult<DavPut> {
        val type = runCatching { ContentType.parse(contentType) }.getOrDefault(ContentType.Application.OctetStream)
        return call("PUT", {
            client.request(root.url(path)) {
                method = HttpMethod.Put
                timeout { requestTimeoutMillis = DAV_PUT_TIMEOUT_MS }
                ifNoneMatch?.let { header(HttpHeaders.IfNoneMatch, it) }
                setBody(ByteArrayContent(bytes, type))
            }
        }) { response ->
            when (val code = response.status.value) {
                in 200..299 -> ApiResult.Success(DavPut.Created(response.headers[OC_ETAG] ?: response.headers[HttpHeaders.ETag]))
                HttpStatusCode.PreconditionFailed.value -> ApiResult.Success(DavPut.Exists)
                else -> failure(code)
            }
        }
    }

    /** One request with the log line and the stats of [get]; a thrown error becomes [ApiError.Network]. */
    private suspend fun <T> call(
        verb: String,
        send: suspend () -> HttpResponse,
        read: suspend (HttpResponse) -> ApiResult<T>,
    ): ApiResult<T> {
        val start = TimeSource.Monotonic.markNow()
        return try {
            val response = send()
            stats?.record(response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L)
            log.info("req $verb dav -> ${response.status.value} ${start.elapsedNow().inWholeMilliseconds}ms")
            read(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            stats?.record(0L)
            log.warn("req $verb dav failed: ${e::class.simpleName}")
            ApiResult.Failure(ApiError.Network(e))
        }
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
}
