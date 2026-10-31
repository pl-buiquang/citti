package app.citti.core.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/**
 * [baseUrl] is the server root and may carry a path prefix (`https://host/cloud/`).
 * [notesApiPath] is joined onto it; override it if the server has no `index.php` in its URLs.
 */
data class NotesApiConfig(
    val baseUrl: String,
    val user: String,
    val appPassword: String,
    val notesApiPath: String = "index.php/apps/notes/api/v1",
) {
    internal fun url(path: String): String = baseUrl.trimEnd('/') + "/" + path.trimStart('/')
}

const val USER_AGENT = "citti (Android)"

internal val apiJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

/** Builds the one HttpClient. Basic auth is sent on every request, without a 401 round trip. */
fun createNotesHttpClient(
    engine: HttpClientEngine,
    config: NotesApiConfig,
): HttpClient {
    val credentials = Base64.Default.encode("${config.user}:${config.appPassword}".encodeToByteArray())
    return HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(apiJson) }
        defaultRequest {
            headers.append(HttpHeaders.Authorization, "Basic $credentials")
            headers.append(HttpHeaders.UserAgent, USER_AGENT)
        }
    }
}

/** The platform engine (OkHttp, HTTP/2). */
expect fun defaultHttpEngine(): HttpClientEngine

fun createNotesHttpClient(config: NotesApiConfig): HttpClient = createNotesHttpClient(defaultHttpEngine(), config)
