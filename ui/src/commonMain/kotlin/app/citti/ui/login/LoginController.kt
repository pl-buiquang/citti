package app.citti.ui.login

import app.citti.core.api.ApiError
import app.citti.core.api.ApiResult
import app.citti.core.auth.Credentials
import app.citti.core.auth.LoginFlow
import app.citti.core.auth.LoginResult
import app.citti.core.auth.LoginSession
import app.citti.core.auth.Verified
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The login calls the controller needs; [LoginFlowBackend] adapts core's [LoginFlow], tests use a fake. */
interface LoginBackend {
    suspend fun start(serverUrl: String): ApiResult<LoginSession>

    suspend fun awaitApproval(session: LoginSession): LoginResult

    suspend fun finish(approved: LoginResult.Approved): ApiResult<Verified>

    suspend fun verifyManual(
        serverUrl: String,
        user: String,
        appPassword: String,
    ): ApiResult<Verified>
}

class LoginFlowBackend(
    private val flow: LoginFlow,
) : LoginBackend {
    override suspend fun start(serverUrl: String) = flow.start(serverUrl)

    override suspend fun awaitApproval(session: LoginSession) = flow.awaitApproval(session)

    override suspend fun finish(approved: LoginResult.Approved) = flow.finish(approved)

    override suspend fun verifyManual(
        serverUrl: String,
        user: String,
        appPassword: String,
    ) = flow.verifyManual(serverUrl, user, appPassword)
}

enum class LoginStep { Idle, Starting, WaitingForBrowser, Verifying }

enum class LoginError { InvalidUrl, NotesAppMissing, WrongCredentials, TimedOut, Offline, Server, Unknown }

data class LoginUiState(
    val serverUrl: String = "",
    val user: String = "",
    val appPassword: String = "",
    val manual: Boolean = false,
    val step: LoginStep = LoginStep.Idle,
    val error: LoginError? = null,
) {
    val busy: Boolean get() = step != LoginStep.Idle

    /** The app password is a secret: it never reaches a log. */
    override fun toString() = "LoginUiState(serverUrl=$serverUrl, user=$user, appPassword=***, manual=$manual, step=$step, error=$error)"
}

/**
 * Login state machine: Flow v2 (start, open the URL, poll, verify, save) and the manual form.
 *
 * [openUrl] is supplied by the app (browser / Custom Tab). [onSignedIn] stores the credentials and
 * (re)builds the sync stack; it runs after the capability check passed.
 */
class LoginController(
    private val scope: CoroutineScope,
    private val backend: LoginBackend,
    private val openUrl: (String) -> Unit,
    private val onSignedIn: suspend (Credentials) -> Unit,
    initialServerUrl: String = "",
) {
    private val _state = MutableStateFlow(LoginUiState(serverUrl = initialServerUrl))
    val state: StateFlow<LoginUiState> = _state.asStateFlow()
    private var job: Job? = null

    fun onServerUrl(value: String) = _state.update { it.copy(serverUrl = value, error = null) }

    fun onUser(value: String) = _state.update { it.copy(user = value, error = null) }

    fun onAppPassword(value: String) = _state.update { it.copy(appPassword = value, error = null) }

    fun showManual(manual: Boolean) {
        if (!_state.value.busy) _state.update { it.copy(manual = manual, error = null) }
    }

    fun signInWithBrowser() {
        if (_state.value.busy) return
        val url = _state.value.serverUrl
        launch {
            step(LoginStep.Starting)
            val session =
                when (val r = backend.start(url)) {
                    is ApiResult.Failure -> return@launch fail(r.error.toLoginError())
                    is ApiResult.Success -> r.value
                }
            openUrl(session.loginUrl)
            step(LoginStep.WaitingForBrowser)
            when (val r = backend.awaitApproval(session)) {
                LoginResult.TimedOut -> {
                    fail(LoginError.TimedOut)
                }

                is LoginResult.Failed -> {
                    fail(r.error.toLoginError())
                }

                is LoginResult.Approved -> {
                    step(LoginStep.Verifying)
                    complete(backend.finish(r))
                }
            }
        }
    }

    fun signInManually() {
        val s = _state.value
        if (s.busy) return
        launch {
            step(LoginStep.Verifying)
            complete(backend.verifyManual(s.serverUrl, s.user.trim(), s.appPassword))
        }
    }

    /** Stops the polling or check in progress and returns to the form. */
    fun cancel() {
        job?.cancel()
        job = null
        _state.update { it.copy(step = LoginStep.Idle, error = null) }
    }

    private suspend fun complete(result: ApiResult<Verified>) {
        when (result) {
            is ApiResult.Failure -> {
                fail(result.error.toLoginError())
            }

            is ApiResult.Success -> {
                try {
                    onSignedIn(result.value.credentials)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(LoginError.Unknown)
                    return
                }
                _state.update { it.copy(step = LoginStep.Idle, appPassword = "", error = null) }
            }
        }
    }

    private fun launch(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch { block() }
    }

    private fun step(step: LoginStep) = _state.update { it.copy(step = step, error = null) }

    private fun fail(error: LoginError) = _state.update { it.copy(step = LoginStep.Idle, error = error) }
}

internal fun ApiError.toLoginError(): LoginError =
    when (this) {
        ApiError.InvalidUrl -> LoginError.InvalidUrl
        ApiError.NotesAppMissing -> LoginError.NotesAppMissing
        is ApiError.Auth -> LoginError.WrongCredentials
        is ApiError.Network -> LoginError.Offline
        is ApiError.Server -> LoginError.Server
        else -> LoginError.Unknown
    }
