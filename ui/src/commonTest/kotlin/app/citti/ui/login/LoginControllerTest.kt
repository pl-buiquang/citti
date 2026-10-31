package app.citti.ui.login

import app.citti.core.api.ApiError
import app.citti.core.api.ApiResult
import app.citti.core.api.Capabilities
import app.citti.core.auth.Credentials
import app.citti.core.auth.LoginResult
import app.citti.core.auth.LoginSession
import app.citti.core.auth.Verified
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LoginControllerTest {
    private val session = LoginSession("https://cloud.example/login/flow/abc", "tok", "https://cloud.example/poll")
    private val approved = LoginResult.Approved("https://cloud.example", "paul", "secret-pw")
    private val verified = Verified(Credentials("https://cloud.example", "paul", "secret-pw"), Capabilities(listOf("1.3"), "6.1.0"))

    private class FakeBackend : LoginBackend {
        var startResult: ApiResult<LoginSession> = ApiResult.Failure(ApiError.InvalidUrl)
        var approval = CompletableDeferred<LoginResult>()
        var finishResult: ApiResult<Verified> = ApiResult.Failure(ApiError.NotesAppMissing)
        var manualResult: ApiResult<Verified> = ApiResult.Failure(ApiError.NotesAppMissing)
        var manualArgs: Triple<String, String, String>? = null

        override suspend fun start(serverUrl: String) = startResult

        override suspend fun awaitApproval(session: LoginSession) = approval.await()

        override suspend fun finish(approved: LoginResult.Approved) = finishResult

        override suspend fun verifyManual(
            serverUrl: String,
            user: String,
            appPassword: String,
        ): ApiResult<Verified> {
            manualArgs = Triple(serverUrl, user, appPassword)
            return manualResult
        }
    }

    private class Harness(
        scope: TestScope,
        val backend: FakeBackend = FakeBackend(),
    ) {
        val opened = mutableListOf<String>()
        val saved = mutableListOf<Credentials>()
        val controller =
            LoginController(
                scope = scope.backgroundScope,
                backend = backend,
                openUrl = { opened += it },
                onSignedIn = { saved += it },
                initialServerUrl = "cloud.example",
            )
        val state get() = controller.state.value
    }

    private fun TestScope.harness() = Harness(this)

    @Test
    fun browserFlowOpensUrlWaitsThenSavesCredentials() =
        runTest(UnconfinedTestDispatcher()) {
            val h = harness()
            h.backend.startResult = ApiResult.Success(session)
            h.backend.finishResult = ApiResult.Success(verified)

            h.controller.signInWithBrowser()
            assertEquals(listOf(session.loginUrl), h.opened)
            assertEquals(LoginStep.WaitingForBrowser, h.state.step)

            h.backend.approval.complete(approved)
            advanceUntilIdle()
            assertEquals(listOf(verified.credentials), h.saved)
            assertEquals(LoginStep.Idle, h.state.step)
            assertNull(h.state.error)
        }

    @Test
    fun timeoutShowsErrorAndReturnsToForm() =
        runTest(UnconfinedTestDispatcher()) {
            val h = harness()
            h.backend.startResult = ApiResult.Success(session)
            h.controller.signInWithBrowser()
            h.backend.approval.complete(LoginResult.TimedOut)
            advanceUntilIdle()
            assertEquals(LoginError.TimedOut, h.state.error)
            assertEquals(LoginStep.Idle, h.state.step)
            assertTrue(h.saved.isEmpty())
        }

    @Test
    fun startErrorsMapToLoginErrors() =
        runTest(UnconfinedTestDispatcher()) {
            val cases =
                mapOf(
                    ApiError.InvalidUrl to LoginError.InvalidUrl,
                    ApiError.InsecureUrl to LoginError.InsecureUrl,
                    ApiError.Network(RuntimeException("x")) to LoginError.Offline,
                    ApiError.Server(502) to LoginError.Server,
                    ApiError.Http(404) to LoginError.Unknown,
                )
            for ((api, expected) in cases) {
                val h = harness()
                h.backend.startResult = ApiResult.Failure(api)
                h.controller.signInWithBrowser()
                assertEquals(expected, h.state.error, "for $api")
                assertTrue(h.opened.isEmpty())
            }
        }

    @Test
    fun notesAppMissingAfterApproval() =
        runTest(UnconfinedTestDispatcher()) {
            val h = harness()
            h.backend.startResult = ApiResult.Success(session)
            h.backend.finishResult = ApiResult.Failure(ApiError.NotesAppMissing)
            h.controller.signInWithBrowser()
            h.backend.approval.complete(approved)
            advanceUntilIdle()
            assertEquals(LoginError.NotesAppMissing, h.state.error)
            assertTrue(h.saved.isEmpty())
        }

    @Test
    fun cancelStopsPollingAndAllowsRetry() =
        runTest(UnconfinedTestDispatcher()) {
            val h = harness()
            h.backend.startResult = ApiResult.Success(session)
            h.controller.signInWithBrowser()
            h.controller.cancel()
            assertEquals(LoginStep.Idle, h.state.step)
            h.backend.approval.complete(approved)
            advanceUntilIdle()
            assertTrue(h.saved.isEmpty())
            assertFalse(h.state.busy)
        }

    @Test
    fun manualSuccessSavesAndClearsPassword() =
        runTest(UnconfinedTestDispatcher()) {
            val h = harness()
            h.backend.manualResult = ApiResult.Success(verified)
            h.controller.showManual(true)
            h.controller.onUser(" paul ")
            h.controller.onAppPassword("secret-pw")
            h.controller.signInManually()
            assertEquals(Triple("cloud.example", "paul", "secret-pw"), h.backend.manualArgs)
            assertEquals(listOf(verified.credentials), h.saved)
            assertEquals("", h.state.appPassword)
        }

    @Test
    fun manualWrongCredentialsAndOffline() =
        runTest(UnconfinedTestDispatcher()) {
            val h = harness()
            h.backend.manualResult = ApiResult.Failure(ApiError.Auth(401))
            h.controller.signInManually()
            assertEquals(LoginError.WrongCredentials, h.state.error)
            h.backend.manualResult = ApiResult.Failure(ApiError.Network(RuntimeException("x")))
            h.controller.signInManually()
            assertEquals(LoginError.Offline, h.state.error)
        }

    @Test
    fun saveFailureIsUnknownError() =
        runTest(UnconfinedTestDispatcher()) {
            val backend = FakeBackend().apply { manualResult = ApiResult.Success(verified) }
            val controller =
                LoginController(backgroundScope, backend, {}, { error("keystore") })
            controller.signInManually()
            assertEquals(LoginError.Unknown, controller.state.value.error)
        }

    @Test
    fun stateToStringHidesPassword() {
        assertFalse(LoginUiState(appPassword = "secret-pw").toString().contains("secret-pw"))
    }
}
