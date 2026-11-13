package app.citti.core.api

import kotlinx.serialization.Serializable

/**
 * A Note as the server lists it. A list item can be a stub (`{id}` only, unchanged since `pruneBefore`),
 * so every field but [id] is nullable; check [isStub] first.
 */
@Serializable
data class RemoteNote(
    val id: Long,
    val etag: String? = null,
    /** Epoch seconds. */
    val modified: Long? = null,
    val title: String? = null,
    /** Folder path, `/` separates levels. */
    val category: String? = null,
    val readonly: Boolean? = null,
    val favorite: Boolean? = null,
    /** Relative to the server's notes folder. */
    val internalPath: String? = null,
    val content: String? = null,
    /**
     * True when the server could not read the file (locked, storage error): [content] then holds an error message,
     * not the Note's text, and [etag] is still the file's. [NotesApi.getNote] turns it into [ApiError.Unreadable].
     */
    val error: Boolean = false,
) {
    val isStub: Boolean get() = etag == null && modified == null && title == null
}

@Serializable
data class NewNote(
    val title: String,
    val content: String,
    val category: String = "",
    val favorite: Boolean = false,
)

/** Only non-null fields are sent. */
@Serializable
data class UpdateNote(
    val title: String? = null,
    val content: String? = null,
    val category: String? = null,
    val favorite: Boolean? = null,
)

@Serializable
data class NotesSettings(
    val notesPath: String = "",
    val fileSuffix: String = "",
)

data class Capabilities(
    val apiVersions: List<String>,
    val appVersion: String?,
) {
    /** chunkSize / chunkCursor exist from API 1.2; the real server lists 1.3 and 1.4 only. */
    val supportsChunks: Boolean = apiVersions.any(::isChunkCapable)

    private companion object {
        fun isChunkCapable(version: String): Boolean {
            val parts = version.split('.')
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return major > 1 || (major == 1 && minor >= 2)
        }
    }
}

sealed interface ListResult {
    /** 304 on `If-None-Match`. */
    data object NotModified : ListResult

    /** [nextCursor] is null on the last page. */
    data class Page(
        val notes: List<RemoteNote>,
        val etag: String?,
        val nextCursor: String?,
        val pending: Int?,
    ) : ListResult
}

sealed interface UpdateResult {
    data class Updated(
        val note: RemoteNote,
    ) : UpdateResult

    /** 412: the server Note changed since the ETag we sent. */
    data object PreconditionFailed : UpdateResult
}

sealed interface DeleteResult

/** Delete succeeded, or the Note was already gone (404). */
data object Deleted : DeleteResult

/** 412: the server Note changed since the ETag we sent, so it was not deleted. */
data object DeletePreconditionFailed : DeleteResult

/** An unexpected outcome the sync engine maps to a state (offline, auth, server). */
sealed interface ApiError {
    /** 401 or 403. */
    data class Auth(
        val status: Int,
    ) : ApiError

    /** 5xx. */
    data class Server(
        val status: Int,
    ) : ApiError

    /** 404 where the resource was expected to exist. */
    data object NotFound : ApiError

    /**
     * The server could not read the Note right now: 423 Locked, or a 200 whose `error` is true. Transient; the
     * text is fetched again later.
     */
    data object Unreadable : ApiError

    /** Any other unexpected status. */
    data class Http(
        val status: Int,
    ) : ApiError

    /** Unreadable body. */
    data class BadResponse(
        val message: String?,
    ) : ApiError

    /** No connection, DNS, TLS, timeout. */
    data class Network(
        val cause: Throwable,
    ) : ApiError

    /** A WebDAV request before the first sync stored the user id and the Notes folder. */
    data object DavNotReady : ApiError

    /** Login: the credentials work but the Notes app is not installed or enabled on the server. */
    data object NotesAppMissing : ApiError

    /** Login: the entered server address is not usable. */
    data object InvalidUrl : ApiError

    /** Login: the server address, or a URL the server sent back, is not https. */
    data object InsecureUrl : ApiError
}

sealed interface ApiResult<out T> {
    data class Success<T>(
        val value: T,
    ) : ApiResult<T>

    data class Failure(
        val error: ApiError,
    ) : ApiResult<Nothing>
}
