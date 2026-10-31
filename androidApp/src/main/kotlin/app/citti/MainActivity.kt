package app.citti

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.citti.ui.list.NoteListController
import app.citti.ui.list.NoteListScreen
import app.citti.ui.login.LoginController
import app.citti.ui.login.LoginFlowBackend
import app.citti.ui.login.LoginScreen
import app.citti.ui.theme.CittiTheme
import kotlinx.coroutines.launch

/** State-based navigation: not signed in shows Login, signed in shows the debug list. */
class MainActivity : ComponentActivity() {
    private val container get() = (application as CittiApp).container

    // Controllers live in the Activity-independent scope of the container, one per sign-in session.
    private var loginController: LoginController? = null
    private var listController: NoteListController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CittiTheme {
                val signedIn by container.signedIn.collectAsState()
                when (signedIn) {
                    null -> Box(Modifier.fillMaxSize().background(CittiTheme.colors.bg))
                    false -> LoginRoute()
                    true -> ListRoute()
                }
            }
        }
    }

    @Composable
    private fun LoginRoute() {
        val controller = remember { loginController ?: newLoginController().also { loginController = it } }
        val state by controller.state.collectAsState()
        LoginScreen(
            state = state,
            onServerUrl = controller::onServerUrl,
            onUser = controller::onUser,
            onAppPassword = controller::onAppPassword,
            onSignInWithBrowser = controller::signInWithBrowser,
            onSignInManually = controller::signInManually,
            onShowManual = controller::showManual,
            onCancel = controller::cancel,
        )
    }

    @Composable
    private fun ListRoute() {
        val controller = remember { listController ?: newListController().also { listController = it } }
        val state by controller.state.collectAsState()
        LaunchedEffect(controller) { controller.onShown() }
        NoteListScreen(
            state = state,
            nowSeconds = { System.currentTimeMillis() / 1000 },
            onSyncNow = controller::syncNow,
            onSignInAgain = {
                container.appScope.launch {
                    container.requireSignIn()
                    listController = null
                }
            },
            // W8b: open Settings here (Account, Sync, Exclusions, sign out).
            onOpenSettings = {},
        )
    }

    private fun newLoginController() =
        LoginController(
            scope = container.appScope,
            backend = LoginFlowBackend(container.loginFlow),
            openUrl = ::openInBrowser,
            onSignedIn = {
                container.onSignedIn(it)
                listController = null
            },
        )

    private fun newListController() =
        NoteListController(
            scope = container.appScope,
            notes = container.noteListItems(),
            status = container.syncStatus,
            sync = container::sync,
        )

    private fun openInBrowser(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
