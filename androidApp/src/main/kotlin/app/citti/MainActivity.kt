package app.citti

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import app.citti.core.settings.ExclusionKind
import app.citti.core.settings.MainMode
import app.citti.ui.browse.BrowseActions
import app.citti.ui.browse.BrowseController
import app.citti.ui.browse.BrowseScreen
import app.citti.ui.browse.BrowseView
import app.citti.ui.components.breadcrumbPaths
import app.citti.ui.login.LoginController
import app.citti.ui.login.LoginFlowBackend
import app.citti.ui.login.LoginScreen
import app.citti.ui.nav.Navigator
import app.citti.ui.nav.Route
import app.citti.ui.nav.entryId
import app.citti.ui.search.SearchActions
import app.citti.ui.search.SearchController
import app.citti.ui.search.SearchScreen
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
import kotlinx.coroutines.withContext

/**
 * Not signed in shows Login. Signed in shows the back stack of [Navigator] (List, Tree, Search, Settings), saved across
 * process death; Login is not a route.
 */
class MainActivity : ComponentActivity() {
    private val container get() = (application as CittiApp).container

    // Controllers live in the Activity-independent scope of the container, one per sign-in session.
    private var loginController: LoginController? = null
    private var browseController: BrowseController? = null

    // One scope per controller, cancelled when the controller is dropped, so no stateIn job outlives it.
    private var browseScope: CoroutineScope? = null

    /** The controllers of the other route entries (Search, Settings), one scope each. */
    private val routes = RouteHost(::newScope)

    private val titleCollator by lazy { localeTitleCollator() }
    private val rowDates by lazy { JavaTimeRowDateFormatter() }

    // POST_NOTIFICATIONS: asked once, never blocks anything; the sync works without it.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun askNotificationPermissionOnce() {
        val prefs = getSharedPreferences("prompts", MODE_PRIVATE)
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val asked = prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)
        if (shouldAskNotificationPermission(Build.VERSION.SDK_INT, granted, asked)) {
            prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CittiTheme {
                val signedIn by container.signedIn.collectAsState()
                when (signedIn) {
                    null -> Blank()
                    false -> LoginRoute()
                    true -> SignedIn()
                }
            }
        }
    }

    @Composable
    private fun Blank() {
        Box(Modifier.fillMaxSize().background(CittiTheme.colors.bg))
    }

    /** The signed-in back stack. It leaves the composition at sign-out, so the next session starts at the root. */
    @Composable
    private fun SignedIn() {
        val mode by produceState<MainMode?>(null) { value = withContext(Dispatchers.IO) { container.uiSettings.mainMode() } }
        val root =
            when (mode ?: return Blank()) {
                MainMode.LIST -> Route.List()
                MainMode.TREE -> Route.Tree()
            }
        val navigator =
            rememberSaveable(saver = Saver({ it.encode() }, { Navigator(Navigator.decode(it) ?: listOf(root)) })) {
                Navigator(listOf(root))
            }
        val stack by navigator.stack.collectAsState()
        // Per-entry UI state (the scroll position), dropped with the entry.
        val saved = rememberSaveableStateHolder()
        val known = remember { mutableSetOf<String>() }
        LaunchedEffect(stack) {
            routes.retain(stack)
            val keys = stack.mapIndexed(::entryKey).toSet()
            (known - keys).forEach(saved::removeState)
            known.retainAll(keys)
            known.addAll(keys)
        }
        BackHandler(enabled = stack.size > 1) { navigator.pop() }
        val index = stack.lastIndex
        val route = stack.last()
        saved.SaveableStateProvider(entryKey(index, route)) {
            when (route) {
                is Route.List, is Route.Tree -> BrowseRoute(navigator, route)

                Route.Settings -> SettingsRoute(navigator, index)

                is Route.Search -> SearchRoute(navigator, index, route)

                // Not pushed yet: the Note screen comes later in M3.
                is Route.Note -> LaunchedEffect(route) { navigator.pop() }
            }
        }
    }

    private fun entryKey(
        index: Int,
        route: Route,
    ): String = "$index:${route.entryId}"

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
    private fun BrowseRoute(
        navigator: Navigator,
        route: Route,
    ) {
        val controller = remember { browseController ?: newBrowseController().also { browseController = it } }
        val state by controller.state.collectAsState()
        LaunchedEffect(controller) {
            controller.onShown()
            askNotificationPermissionOnce()
        }
        LaunchedEffect(controller, route) { controller.show(route) }
        val view =
            when (route) {
                is Route.Tree -> BrowseView.Tree(route.folder)
                else -> BrowseView.List(favoritesOnly = (route as? Route.List)?.favoritesOnly ?: false)
            }
        BrowseScreen(
            view = view,
            state = state,
            nowSeconds = { System.currentTimeMillis() / 1000 },
            dates = rowDates,
            listState = rememberLazyListState(),
            actions =
                BrowseActions(
                    // The drawer replaces this once it lands; until then ≡ opens Settings.
                    onOpenMenu = { navigator.push(Route.Settings) },
                    onOpenSearch = { navigator.push(Route.Search()) },
                    onShowList = {
                        val root = listOf(Route.List())
                        if (navigator.stack.value != root) {
                            navigator.reset(root)
                            controller.setMode(MainMode.LIST)
                        }
                    },
                    onShowTree = {
                        val root = listOf(Route.Tree())
                        if (navigator.stack.value != root) {
                            navigator.reset(root)
                            controller.setMode(MainMode.TREE)
                        }
                    },
                    onOpenFolder = { navigator.push(Route.Tree(it)) },
                    onOpenBreadcrumb = { path ->
                        if (!navigator.popTo(Route.Tree(path))) navigator.reset(breadcrumbPaths(path).map { Route.Tree(it) })
                    },
                    onOpenNote = null,
                    onBack = { navigator.pop() },
                    onSyncNow = controller::syncNow,
                    onSignInAgain = { signInAgain() },
                ),
        )
    }

    @Composable
    private fun SearchRoute(
        navigator: Navigator,
        index: Int,
        route: Route.Search,
    ) {
        val controller = remember(index) { routes.controller(index, route) { newSearchController(it, route.query) } }
        val state by controller.state.collectAsState()
        // The route keeps the query, so it survives process death.
        val keep: (String) -> Unit = { text -> if (navigator.top is Route.Search) navigator.replaceTop(Route.Search(text)) }
        SearchScreen(
            state = state,
            nowSeconds = { System.currentTimeMillis() / 1000 },
            dates = rowDates,
            listState = rememberLazyListState(),
            actions =
                SearchActions(
                    onQuery = {
                        controller.onQuery(it)
                        keep(it)
                    },
                    onClear = {
                        controller.clear()
                        keep("")
                    },
                    onVisible = controller::onVisible,
                    onOpenNote = null,
                    onBack = { navigator.pop() },
                ),
        )
    }

    @Composable
    private fun SettingsRoute(
        navigator: Navigator,
        index: Int,
    ) {
        val account by produceState<AccountInfo?>(null) { value = container.accountInfo() }
        val info = account ?: return
        val controller = remember(index) { routes.controller(index, Route.Settings) { newSettingsController(it, info) } }
        val state by controller.state.collectAsState()
        BackHandler { if (!controller.onBack()) navigator.pop() }
        SettingsScreen(
            state = state,
            nowSeconds = { System.currentTimeMillis() / 1000 },
            actions =
                SettingsActions(
                    onBack = { if (!controller.onBack()) navigator.pop() },
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

    /** Sign out or sign in again: every controller of the session goes, on the main thread like the composition. */
    private fun endSession() {
        runOnUiThread {
            routes.clear()
            browseScope?.cancel()
            browseScope = null
            browseController = null
        }
    }

    private fun signInAgain() {
        container.appScope.launch {
            container.requireSignIn()
            endSession()
        }
    }

    private fun newScope() = CoroutineScope(SupervisorJob(container.appScope.coroutineContext[Job]) + Dispatchers.Default)

    private fun newSettingsController(
        scope: CoroutineScope,
        account: AccountInfo,
    ): SettingsController =
        SettingsController(
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
                endSession()
            },
            signInAgain = {
                container.requireSignIn()
                endSession()
            },
        )

    private fun newLoginController() =
        LoginController(
            scope = container.appScope,
            backend = LoginFlowBackend(container.loginFlow),
            openUrl = ::openInBrowser,
            onSignedIn = {
                container.onSignedIn(it)
                endSession()
            },
        )

    private fun newSearchController(
        scope: CoroutineScope,
        query: String,
    ) = SearchController(
        scope = scope,
        hits = container.noteSearch::hits,
        snippets = container.noteSearch::snippets,
        initialQuery = query,
        log = AndroidSyncLog,
    )

    private fun newBrowseController() =
        BrowseController(
            scope = newScope().also { browseScope = it },
            listRows = container::listRows,
            folderCounts = container.folderCounts(),
            folderNotes = container::folderNotes,
            status = container.syncStatus,
            mainMode = container.uiSettings.mainModeFlow(),
            setMainMode = { withContext(Dispatchers.IO) { container.uiSettings.setMainMode(it) } },
            collator = titleCollator,
            sync = container::sync,
        )

    private fun openInBrowser(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private companion object {
        const val KEY_ASKED_NOTIFICATIONS = "asked-notifications"
    }
}
