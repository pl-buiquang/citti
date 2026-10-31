package app.citti.core.auth

import app.citti.core.api.ApiError
import app.citti.core.api.ApiResult
import app.citti.core.api.Capabilities
import app.citti.core.api.NotesApi
import app.citti.core.api.NotesApiConfig
import app.citti.core.api.USER_AGENT
import app.citti.core.api.createNotesHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Returned by `POST /index.php/login/v2`. Open [loginUrl] in the browser, then call [LoginFlow.awaitApproval]. */
data class LoginSession(
    val loginUrl: String,
    val pollToken: String,
    val pollEndpoint: String,
)

sealed interface LoginResult {
    /** [server] is the URL from the response; it may differ from the one typed. */
    data class Approved(
        val server: String,
        val loginName: String,
        val appPassword: String,
    ) : LoginResult {
        override fun toString() = "Approved(server=$server, loginName=$loginName, appPassword=***)"
    }

    data object TimedOut : LoginResult

    /** The server answered the poll with something unexpected. */
    data class Failed(
        val error: ApiError,
    ) : LoginResult
}

/** Credentials that passed the check, plus the server facts to store. */
data class Verified(
    val credentials: Credentials,
    val capabilities: Capabilities,
) {
    val supportsChunks: Boolean get() = capabilities.supportsChunks
}

/**
 * Login Flow v2 plus the manual fallback (ADR 0005).
 *
 * [client] sends the flow requests and carries no credentials. [apiClientFactory] builds the
 * authenticated client for the capability check. [delayFn] is injectable for tests.
 */
class LoginFlow(
    private val client: HttpClient,
    private val apiClientFactory: (NotesApiConfig) -> HttpClient = { createNotesHttpClient(it) },
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun start(serverUrl: String): ApiResult<LoginSession> {
        val base =
            when (val checked = httpsBase(serverUrl)) {
                is ApiResult.Failure -> return checked
                is ApiResult.Success -> checked.value
            }
        return try {
            val response = client.post("$base/index.php/login/v2") { header(HttpHeaders.UserAgent, USER_AGENT) }
            if (response.status.value !in 200..299) return ApiResult.Failure(errorFor(response.status.value))
            val root = flowJson.parseToJsonElement(response.bodyAsText()).jsonObject
            val poll = root["poll"] as? JsonObject
            val session =
                LoginSession(
                    loginUrl = root.string("login"),
                    pollToken = poll.string("token"),
                    pollEndpoint = poll.string("endpoint"),
                )
            if (session.loginUrl.isEmpty() || session.pollToken.isEmpty() || session.pollEndpoint.isEmpty()) {
                ApiResult.Failure(ApiError.BadResponse("incomplete login/v2 response"))
            } else if (!isHttps(session.loginUrl) || !isHttps(session.pollEndpoint)) {
                // The browser opens loginUrl and the poll token goes to pollEndpoint: never over cleartext or another scheme.
                ApiResult.Failure(ApiError.InsecureUrl)
            } else {
                ApiResult.Success(session)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SerializationException) {
            ApiResult.Failure(ApiError.BadResponse(e.message))
        } catch (e: IllegalArgumentException) {
            ApiResult.Failure(ApiError.BadResponse(e.message))
        } catch (e: Throwable) {
            ApiResult.Failure(ApiError.Network(e))
        }
    }

    /**
     * Polls until the user approves (200), [timeoutMs] elapses, or the caller cancels. 404 means "not yet".
     * Elapsed time is the sum of the waits, so tests on virtual time are exact.
     */
    suspend fun awaitApproval(
        session: LoginSession,
        intervalMs: Long = 2000,
        timeoutMs: Long = 20 * 60_000,
    ): LoginResult {
        var elapsed = 0L
        while (true) {
            try {
                val response =
                    client.submitForm(
                        url = session.pollEndpoint,
                        formParameters = parameters { append("token", session.pollToken) },
                    ) { header(HttpHeaders.UserAgent, USER_AGENT) }
                when (val code = response.status.value) {
                    200 -> return parseApproved(response)
                    404 -> Unit
                    else -> return LoginResult.Failed(errorFor(code))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A network blip while the user is in the browser must not end the flow; the timeout does.
            }
            if (elapsed >= timeoutMs) return LoginResult.TimedOut
            delayFn(intervalMs)
            elapsed += intervalMs
        }
    }

    /** Checks the credentials against the server and that the Notes app is there. Used by the manual form. */
    suspend fun verifyManual(
        serverUrl: String,
        user: String,
        appPassword: String,
    ): ApiResult<Verified> {
        val base =
            when (val checked = httpsBase(serverUrl)) {
                is ApiResult.Failure -> return checked
                is ApiResult.Success -> checked.value
            }
        val config = NotesApiConfig(base, user, appPassword)
        val apiClient = apiClientFactory(config)
        try {
            return when (val result = NotesApi(apiClient, config).capabilities()) {
                is ApiResult.Failure -> {
                    result
                }

                is ApiResult.Success -> {
                    val caps = result.value
                    if (caps.apiVersions.isEmpty() && caps.appVersion == null) {
                        ApiResult.Failure(ApiError.NotesAppMissing)
                    } else {
                        ApiResult.Success(Verified(Credentials(base, user, appPassword), caps))
                    }
                }
            }
        } finally {
            apiClient.close()
        }
    }

    /** The same check after Flow v2 success, against the server the response named. */
    suspend fun finish(approved: LoginResult.Approved): ApiResult<Verified> =
        verifyManual(approved.server, approved.loginName, approved.appPassword)

    private suspend fun parseApproved(response: HttpResponse): LoginResult =
        try {
            val root = flowJson.parseToJsonElement(response.bodyAsText()).jsonObject
            val server = root.string("server")
            val user = root.string("loginName")
            val password = root.string("appPassword")
            if (server.isEmpty() || user.isEmpty() || password.isEmpty()) {
                LoginResult.Failed(ApiError.BadResponse("incomplete poll response"))
            } else {
                LoginResult.Approved(server, user, password)
            }
        } catch (e: SerializationException) {
            LoginResult.Failed(ApiError.BadResponse(e.message))
        } catch (e: IllegalArgumentException) {
            LoginResult.Failed(ApiError.BadResponse(e.message))
        }

    private fun errorFor(code: Int): ApiError =
        when (code) {
            401, 403 -> ApiError.Auth(code)
            in 500..599 -> ApiError.Server(code)
            else -> ApiError.Http(code)
        }
}

private val flowJson = Json { ignoreUnknownKeys = true }

private fun JsonObject?.string(key: String): String = (this?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

/**
 * Adds `https://` when there is no scheme, trims whitespace and trailing slashes, keeps a path prefix
 * (`host/cloud`). Null for blank input.
 */
fun normalizeServerUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val withScheme = if (SCHEME.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
    val authority = withScheme.substringAfter("://").trimEnd('/')
    return if (authority.isEmpty()) null else withScheme.trimEnd('/')
}

/** True for an `https://` URL (any case). The only scheme citti talks to or opens. */
fun isHttps(url: String): Boolean = url.startsWith("https://", ignoreCase = true)

/** The normalized server URL, refused when blank ([ApiError.InvalidUrl]) or not https ([ApiError.InsecureUrl]). */
internal fun httpsBase(input: String): ApiResult<String> {
    val base = normalizeServerUrl(input) ?: return ApiResult.Failure(ApiError.InvalidUrl)
    return if (isHttps(base)) ApiResult.Success(base) else ApiResult.Failure(ApiError.InsecureUrl)
}
