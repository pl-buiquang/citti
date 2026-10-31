package app.citti.core.api

/** Short, body-free description for logs: the kind and the HTTP status when there is one. */
fun ApiError.logText(): String =
    when (this) {
        is ApiError.Auth -> "Auth(http $status)"
        is ApiError.Server -> "Server(http $status)"
        ApiError.NotFound -> "NotFound(http 404)"
        is ApiError.Http -> "Http(http $status)"
        is ApiError.BadResponse -> "BadResponse"
        ApiError.NotesAppMissing -> "NotesAppMissing"
        ApiError.InvalidUrl -> "InvalidUrl"
        ApiError.InsecureUrl -> "InsecureUrl"
        is ApiError.Network -> "Network(${cause::class.simpleName})"
    }
