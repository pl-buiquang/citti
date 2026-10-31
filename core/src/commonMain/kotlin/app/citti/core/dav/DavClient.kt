package app.citti.core.dav

import app.citti.core.api.ApiError
import app.citti.core.api.ApiResult
import app.citti.core.api.RequestStats
import app.citti.core.log.SyncLog
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlin.time.TimeSource

/** Where the Vault is on the server: `<baseUrl>/remote.php/dav/files/<userId>/<notesPath>/`. */
data class DavRoot(
    val baseUrl: String,
    val userId: String,
    val notesPath: String,
) {
    internal fun url(path: VaultPath): String {
        val notes = notesPath.split('/').filter { it.isNotEmpty() }
        val segments = listOf("remote.php", "dav", "files", userId) + notes + path.value.split('/')
        return baseUrl.trimEnd('/') + "/" + segments.joinToString("/") { encodeSegment(it) }
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

/**
 * Authenticated WebDAV access to the Vault, over the app's one HTTP client (Basic auth and User-Agent are set
 * there). Outcomes are values, never exceptions. M4 only reads; M5 adds upload. Nothing outside this class builds
 * a WebDAV URL.
 */
class DavClient(
    private val client: HttpClient,
    private val root: suspend () -> DavRoot?,
    private val log: SyncLog = SyncLog.None,
    private val stats: RequestStats? = null,
) {
    /** `GET` of [path]; with [ifNoneMatch] a 304 gives [DavGet.NotModified]. No request while the root is unknown. */
    suspend fun get(
        path: VaultPath,
        ifNoneMatch: String? = null,
    ): ApiResult<DavGet> {
        val root = root() ?: return ApiResult.Failure(ApiError.DavNotReady)
        val start = TimeSource.Monotonic.markNow()
        return try {
            val response: HttpResponse =
                client.get(root.url(path)) {
                    ifNoneMatch?.let { header(HttpHeaders.IfNoneMatch, it) }
                }
            stats?.record(response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L)
            log.info("req GET dav -> ${response.status.value} ${start.elapsedNow().inWholeMilliseconds}ms")
            when (val code = response.status.value) {
                HttpStatusCode.NotModified.value -> {
                    ApiResult.Success(DavGet.NotModified)
                }

                in 200..299 -> {
                    val etag = response.headers[HttpHeaders.ETag]
                    ApiResult.Success(DavGet.Body(response.body<ByteArray>(), etag, response.headers[HttpHeaders.ContentType]))
                }

                401, 403 -> {
                    ApiResult.Failure(ApiError.Auth(code))
                }

                404 -> {
                    ApiResult.Failure(ApiError.NotFound)
                }

                in 500..599 -> {
                    ApiResult.Failure(ApiError.Server(code))
                }

                else -> {
                    ApiResult.Failure(ApiError.Http(code))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            stats?.record(0L)
            log.warn("req GET dav failed: ${e::class.simpleName}")
            ApiResult.Failure(ApiError.Network(e))
        }
    }
}
