package app.citti.core.api

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

const val HEADER_CHUNK_CURSOR = "X-Notes-Chunk-Cursor"
const val HEADER_CHUNK_PENDING = "X-Notes-Chunk-Pending"

/** Nextcloud Notes API. Expected HTTP outcomes are values, never exceptions. */
class NotesApi(
    private val client: HttpClient,
    private val config: NotesApiConfig,
) {
    private fun notesUrl(path: String = "") = config.url(config.notesApiPath.trim('/') + path)

    suspend fun capabilities(): ApiResult<Capabilities> =
        call({
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
        call({
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
        call({ client.get(notesUrl("/notes/$id")) }) { response ->
            if (!response.status.isOk()) return@call fail(response)
            parse { decode(response, RemoteNote.serializer()) }
        }

    suspend fun createNote(note: NewNote): ApiResult<RemoteNote> =
        call({ client.post(notesUrl("/notes")) { json(note, NewNote.serializer()) } }) { response ->
            if (!response.status.isOk()) return@call fail(response)
            parse { decode(response, RemoteNote.serializer()) }
        }

    suspend fun updateNote(
        id: Long,
        update: UpdateNote,
        ifMatch: String,
    ): ApiResult<UpdateResult> =
        call({
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

    suspend fun deleteNote(id: Long): ApiResult<Deleted> =
        call({ client.delete(notesUrl("/notes/$id")) }) { response ->
            if (response.status.isOk() || response.status == HttpStatusCode.NotFound) {
                ApiResult.Success(Deleted)
            } else {
                fail(response)
            }
        }

    suspend fun settings(): ApiResult<NotesSettings> =
        call({ client.get(notesUrl("/settings")) }) { response ->
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
        request: suspend () -> HttpResponse,
        handle: suspend (HttpResponse) -> ApiResult<T>,
    ): ApiResult<T> =
        try {
            handle(request())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ApiResult.Failure(ApiError.Network(e))
        }
}

private fun HttpStatusCode.isOk() = value in 200..299
