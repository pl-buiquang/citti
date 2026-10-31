package app.citti

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.citti.core.settings.ExclusionKind
import app.citti.ui.list.NoteListController
import app.citti.ui.list.NoteListScreen
import app.citti.ui.login.LoginController
import app.citti.ui.login.LoginFlowBackend
import app.citti.ui.login.LoginScreen
import app.citti.ui.settings.AccountInfo
import app.citti.ui.settings.SettingsActions
import app.citti.ui.settings.SettingsController
import app.citti.ui.settings.SettingsScreen
import app.citti.ui.theme.CittiTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** State-based navigation: not signed in shows Login, signed in shows the debug list, Settings on top of it. */
class MainActivity : ComponentActivity() {
    private val container get() = (application as CittiApp).container

    // Controllers live in the Activity-independent scope of the container, one per sign-in session.
    private var loginController: LoginController? = null
    private var listController: NoteListController? = null
    private var settingsController: SettingsController? = null

    // One scope per controller, cancelled when the controller is dropped, so no stateIn job outlives it.
    private var listScope: CoroutineScope? = null
    private var settingsScope: CoroutineScope? = null
    private var settingsOpen by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CittiTheme {
                val signedIn by container.signedIn.collectAsState()
                when (signedIn) {
                    null -> Box(Modifier.fillMaxSize().background(CittiTheme.colors.bg))
                    false -> LoginRoute()
                    true -> if (settingsOpen) SettingsRoute() else ListRoute()
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
            onSignInAgain = { signInAgain() },
            onOpenSettings = { settingsOpen = true },
        )
    }

    @Composable
    private fun SettingsRoute() {
        val account by produceState<AccountInfo?>(null) { value = container.accountInfo() }
        val info = account ?: return
        val controller = remember { settingsController ?: newSettingsController(info).also { settingsController = it } }
        val state by controller.state.collectAsState()
        BackHandler { if (!controller.onBack()) closeSettings() }
        SettingsScreen(
            state = state,
            nowSeconds = { System.currentTimeMillis() / 1000 },
            actions =
                SettingsActions(
                    onBack = { if (!controller.onBack()) closeSettings() },
                    onInterval = controller::setInterval,
                    onNetwork = controller::setNetwork,
                    onSyncNow = controller::syncNow,
                    onOpenPicker = controller::openPicker,
                    onAddFolder = controller::addFolder,
                    onRemoveFolder = controller::removeFolder,
                    onClearFolderError = controller::clearFolderError,
                    onAskSignOut = controller::askSignOut,
                    onSignInAgain = controller::signInAgain,
                    onConfirm = controller::confirm,
                    onDismissConfirm = controller::dismissConfirm,
                ),
        )
    }

    private fun closeSettings() {
        settingsOpen = false
        settingsScope?.cancel()
        settingsScope = null
        settingsController = null
    }

    private fun dropListController() {
        listScope?.cancel()
        listScope = null
        listController = null
    }

    private fun signInAgain() {
        container.appScope.launch {
            container.requireSignIn()
            closeSettings()
            dropListController()
        }
    }

    private fun newScope() = CoroutineScope(SupervisorJob(container.appScope.coroutineContext[Job]) + Dispatchers.Default)

    private fun newSettingsController(account: AccountInfo): SettingsController {
        val scope = newScope().also { settingsScope = it }
        return SettingsController(
            scope = scope,
            account = account,
            store = container.settingsStore,
            interval = container.syncSettings.intervalMinutesFlow(),
            network = container.syncSettings.networkFlow(),
            status = container.syncStatus,
            unsynced = container.exclusions.flow(ExclusionKind.UNSYNCED),
            hidden = container.exclusions.flow(ExclusionKind.HIDDEN),
            knownFolders = container.exclusions.knownFolders(),
            sync = container::sync,
            signOut = {
                container.signOut()
                closeSettings()
                dropListController()
            },
            signInAgain = {
                container.requireSignIn()
                closeSettings()
                dropListController()
            },
        )
    }

    private fun newLoginController() =
        LoginController(
            scope = container.appScope,
            backend = LoginFlowBackend(container.loginFlow),
            openUrl = ::openInBrowser,
            onSignedIn = {
                container.onSignedIn(it)
                dropListController()
            },
        )

    private fun newListController() =
        NoteListController(
            scope = newScope().also { listScope = it },
            notes = container.noteListItems(),
            status = container.syncStatus,
            sync = container::sync,
        )

    private fun openInBrowser(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
