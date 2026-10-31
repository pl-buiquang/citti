package app.citti.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import app.citti.ui.components.Banner
import app.citti.ui.components.BannerKind
import app.citti.ui.components.ButtonKind
import app.citti.ui.components.CittiButton
import app.citti.ui.components.CittiTextButton
import app.citti.ui.components.CittiTextField
import app.citti.ui.components.colored
import app.citti.ui.resources.Res
import app.citti.ui.resources.app_name
import app.citti.ui.resources.login_app_password_label
import app.citti.ui.resources.login_cancel
import app.citti.ui.resources.login_error_invalid_url
import app.citti.ui.resources.login_error_notes_missing
import app.citti.ui.resources.login_error_offline
import app.citti.ui.resources.login_error_server
import app.citti.ui.resources.login_error_timed_out
import app.citti.ui.resources.login_error_unknown
import app.citti.ui.resources.login_error_wrong_credentials
import app.citti.ui.resources.login_server_hint
import app.citti.ui.resources.login_server_label
import app.citti.ui.resources.login_sign_in
import app.citti.ui.resources.login_sign_in_browser
import app.citti.ui.resources.login_starting
import app.citti.ui.resources.login_use_app_password
import app.citti.ui.resources.login_use_browser
import app.citti.ui.resources.login_user_label
import app.citti.ui.resources.login_verifying
import app.citti.ui.resources.login_waiting_browser
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSpacing
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Stateless login screen (design brief 1). */
@Composable
fun LoginScreen(
    state: LoginUiState,
    onServerUrl: (String) -> Unit,
    onUser: (String) -> Unit,
    onAppPassword: (String) -> Unit,
    onSignInWithBrowser: () -> Unit,
    onSignInManually: () -> Unit,
    onShowManual: (Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(CittiTheme.colors.bg)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CittiTheme.shape.gutter, vertical = NotesSpacing.space8),
        verticalArrangement = Arrangement.spacedBy(NotesSpacing.space4),
    ) {
        BasicText(stringResource(Res.string.app_name), style = CittiTheme.typography.title.colored())
        CittiTextField(
            value = state.serverUrl,
            onValueChange = onServerUrl,
            label = stringResource(Res.string.login_server_label),
            placeholder = stringResource(Res.string.login_server_hint),
            enabled = !state.busy,
            keyboardType = KeyboardType.Uri,
        )
        if (state.manual) {
            CittiTextField(
                value = state.user,
                onValueChange = onUser,
                label = stringResource(Res.string.login_user_label),
                enabled = !state.busy,
            )
            CittiTextField(
                value = state.appPassword,
                onValueChange = onAppPassword,
                label = stringResource(Res.string.login_app_password_label),
                enabled = !state.busy,
                secret = true,
            )
        }
        state.error?.let { Banner(stringResource(it.message()), BannerKind.Error) }
        progressText(state.step)?.let {
            BasicText(stringResource(it), style = CittiTheme.typography.body.colored(muted = true))
        }
        when {
            state.step == LoginStep.WaitingForBrowser -> {
                CittiButton(stringResource(Res.string.login_cancel), onCancel, kind = ButtonKind.Quiet)
            }

            state.manual -> {
                CittiButton(stringResource(Res.string.login_sign_in), onSignInManually, enabled = !state.busy)
                CittiTextButton(stringResource(Res.string.login_use_browser), { onShowManual(false) })
            }

            else -> {
                CittiButton(stringResource(Res.string.login_sign_in_browser), onSignInWithBrowser, enabled = !state.busy)
                CittiTextButton(stringResource(Res.string.login_use_app_password), { onShowManual(true) })
            }
        }
    }
}

private fun progressText(step: LoginStep): StringResource? =
    when (step) {
        LoginStep.Idle -> null
        LoginStep.Starting -> Res.string.login_starting
        LoginStep.WaitingForBrowser -> Res.string.login_waiting_browser
        LoginStep.Verifying -> Res.string.login_verifying
    }

private fun LoginError.message(): StringResource =
    when (this) {
        LoginError.InvalidUrl -> Res.string.login_error_invalid_url
        LoginError.NotesAppMissing -> Res.string.login_error_notes_missing
        LoginError.WrongCredentials -> Res.string.login_error_wrong_credentials
        LoginError.TimedOut -> Res.string.login_error_timed_out
        LoginError.Offline -> Res.string.login_error_offline
        LoginError.Server -> Res.string.login_error_server
        LoginError.Unknown -> Res.string.login_error_unknown
    }
