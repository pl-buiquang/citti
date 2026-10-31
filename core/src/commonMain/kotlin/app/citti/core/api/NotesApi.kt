package app.citti.core.api

import app.citti.core.log.SyncLog
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.time.TimeSource

const val HEADER_CHUNK_CURSOR = "X-Notes-Chunk-Cursor"
const val HEADER_CHUNK_PENDING = "X-Notes-Chunk-Pending"

/** Nextcloud Notes API. Expected HTTP outcomes are values, never exceptions. */
class NotesApi(
    private val client: HttpClient,
    private val config: NotesApiConfig,
    private val log: SyncLog = SyncLog.None,
) {
    /** Counts every request made through this API; the sync engine reads it for its summary line. */
    val stats = RequestStats()

    private fun notesUrl(path: String = "") = config.url(config.notesApiPath.trim('/') + path)

    suspend fun capabilities(): ApiResult<Capabilities> =
        call("GET /capabilities", {
            client.get(config.url("ocs/v2.php/cloud/capabilities")) {
                parameter("format", "json")
                header("OCS-APIRequest", "true")
            }
        }) { response ->
            if (!response.status.isOk()) return@call fail(response)
            parse {
                val notes =
                    apiJson
                        .parseToJsonElement(response.bodyAsText())
                        .jsonObject["ocs"]
                        ?.jsonObject
                        ?.get("data")
                        ?.jsonObject
                        ?.get("capabilities")
                        ?.jsonObject
                        ?.get("notes")
                        ?.jsonObject
                val versions =
                    (notes?.get("api_version") as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        .orEmpty()
                Capabilities(versions, (notes?.get("version") as? JsonPrimitive)?.contentOrNull)
            }
        }

    suspend fun listNotes(
        excludeContent: Boolean = true,
        pruneBefore: Long? = null,
        chunkSize: Int? = null,
        chunkCursor: String? = null,
        ifNoneMatch: String? = null,
    ): ApiResult<ListResult> =
        call("GET /notes", {
            client.get(notesUrl("/notes")) {
                if (excludeContent) parameter("exclude", "content")
                pruneBefore?.let { parameter("pruneBefore", it) }
                chunkSize?.let { parameter("chunkSize", it) }
                chunkCursor?.let { parameter("chunkCursor", it) }
                ifNoneMatch?.let { header(HttpHeaders.IfNoneMatch, it) }
            }
        }) { response ->
            when {
                response.status == HttpStatusCode.NotModified -> {
                    ApiResult.Success(ListResult.NotModified)
                }

                !response.status.isOk() -> {
                    fail(response)
                }

                else -> {
                    parse {
                        ListResult.Page(
                            notes = decode(response, ListSerializer(RemoteNote.serializer())),
                            etag = response.headers[HttpHeaders.ETag],
                            nextCursor = response.headers[HEADER_CHUNK_CURSOR]?.takeIf { it.isNotEmpty() },
                            pending = response.headers[HEADER_CHUNK_PENDING]?.toIntOrNull(),
                        )
                    }
                }
            }
        }

    suspend fun getNote(id: Long): ApiResult<RemoteNote> =
        call("GET /notes/{id}", { client.get(notesUrl("/notes/$id")) }) { response ->
            if (!response.status.isOk()) return@call fail(response)
            parse { decode(response, RemoteNote.serializer()) }
        }

    suspend fun createNote(note: NewNote): ApiResult<RemoteNote> =
        call("POST /notes", { client.post(notesUrl("/notes")) { json(note, NewNote.serializer()) } }) { response ->
            if (!response.status.isOk()) return@call fail(response)
            parse { decode(response, RemoteNote.serializer()) }
        }

    suspend fun updateNote(
        id: Long,
        update: UpdateNote,
        ifMatch: String,
    ): ApiResult<UpdateResult> =
        call("PUT /notes/{id}", {
            client.put(notesUrl("/notes/$id")) {
                header(HttpHeaders.IfMatch, ifMatch)
                json(update, UpdateNote.serializer())
            }
        }) { response ->
            when {
                response.status == HttpStatusCode.PreconditionFailed -> {
                    ApiResult.Success(UpdateResult.PreconditionFailed)
                }

                !response.status.isOk() -> {
                    fail(response)
                }

                else -> {
                    parse { UpdateResult.Updated(decode(response, RemoteNote.serializer())) }
                }
            }
        }

    /** With [ifMatch], a Note that changed on the server is kept and the result is [DeletePreconditionFailed]. */
    suspend fun deleteNote(
        id: Long,
        ifMatch: String? = null,
    ): ApiResult<DeleteResult> =
        call("DELETE /notes/{id}", {
            client.delete(notesUrl("/notes/$id")) {
                ifMatch?.let { header(HttpHeaders.IfMatch, it) }
            }
        }) { response ->
            when {
                response.status == HttpStatusCode.PreconditionFailed -> {
                    ApiResult.Success(DeletePreconditionFailed)
                }

                response.status.isOk() || response.status == HttpStatusCode.NotFound -> {
                    ApiResult.Success(Deleted)
                }

                else -> {
                    fail(response)
                }
            }
        }

    suspend fun settings(): ApiResult<NotesSettings> =
        call("GET /settings", { client.get(notesUrl("/settings")) }) { response ->
            if (!response.status.isOk()) return@call fail(response)
            parse { decode(response, NotesSettings.serializer()) }
        }

    private fun <T> HttpRequestBuilder.json(
        value: T,
        serializer: KSerializer<T>,
    ) {
        contentType(ContentType.Application.Json)
        setBody(apiJson.encodeToString(serializer, value))
    }

    private suspend fun <T> decode(
        response: HttpResponse,
        serializer: KSerializer<T>,
    ): T = apiJson.decodeFromString(serializer, response.bodyAsText())

    private suspend fun <T> parse(block: suspend () -> T): ApiResult<T> =
        try {
            ApiResult.Success(block())
        } catch (e: SerializationException) {
            ApiResult.Failure(ApiError.BadResponse(e.message))
        } catch (e: IllegalArgumentException) {
            ApiResult.Failure(ApiError.BadResponse(e.message))
        }

    private fun fail(response: HttpResponse): ApiResult.Failure =
        ApiResult.Failure(
            when (val code = response.status.value) {
                401, 403 -> ApiError.Auth(code)
                404 -> ApiError.NotFound
                in 500..599 -> ApiError.Server(code)
                else -> ApiError.Http(code)
            },
        )

    private suspend fun <T> call(
        label: String,
        request: suspend () -> HttpResponse,
        handle: suspend (HttpResponse) -> ApiResult<T>,
    ): ApiResult<T> {
        val start = TimeSource.Monotonic.markNow()
        return try {
            val response = request()
            val bytes = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            stats.record(bytes ?: 0L)
            val note = if (response.status == HttpStatusCode.NotModified) " (not modified)" else ""
            val size = bytes?.let { " ${it}B" }.orEmpty()
            val ms = start.elapsedNow().inWholeMilliseconds
            log.info("req $label${queryNames(response)} -> ${response.status.value}$note$size ${ms}ms")
            handle(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            stats.record(0L)
            log.warn("req $label failed: ${e::class.simpleName} ${start.elapsedNow().inWholeMilliseconds}ms")
            ApiResult.Failure(ApiError.Network(e))
        }
    }

    /** `?exclude&chunkSize=500&chunkCursor`: parameter names only, values just for the harmless ones. */
    private fun queryNames(response: HttpResponse): String {
        val params = response.call.request.url.parameters
        if (params.isEmpty()) return ""
        return params.names().joinToString("&", prefix = "?") { name ->
            if (name in LOGGABLE_VALUES) "$name=${params[name]}" else name
        }
    }
}

private val LOGGABLE_VALUES = setOf("pruneBefore", "chunkSize")

private fun HttpStatusCode.isOk() = value in 200..299
