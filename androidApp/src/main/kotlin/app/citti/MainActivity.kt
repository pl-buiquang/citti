package app.citti

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.citti.core.appearance.MAX_THEME_FILE_BYTES
import app.citti.core.settings.ExclusionKind
import app.citti.core.settings.MainMode
import app.citti.ui.TestTags
import app.citti.ui.appearance.AppearanceActions
import app.citti.ui.appearance.ThemeEditorController
import app.citti.ui.appearance.actions
import app.citti.ui.browse.AppDrawer
import app.citti.ui.browse.BrowseActions
import app.citti.ui.browse.BrowseController
import app.citti.ui.browse.BrowseScreen
import app.citti.ui.browse.BrowseUiState
import app.citti.ui.browse.BrowseView
import app.citti.ui.browse.DrawerActions
import app.citti.ui.browse.rootRoute
import app.citti.ui.browse.switchMode
import app.citti.ui.components.CittiDrawer
import app.citti.ui.components.Snack
import app.citti.ui.components.SnackbarHost
import app.citti.ui.components.SnackbarState
import app.citti.ui.components.breadcrumbPaths
import app.citti.ui.components.rememberCittiDrawerState
import app.citti.ui.create.CoreNoteCreation
import app.citti.ui.create.NewNoteController
import app.citti.ui.create.NewNoteHost
import app.citti.ui.create.actions
import app.citti.ui.create.newNoteFolder
import app.citti.ui.daily.CalendarActions
import app.citti.ui.daily.CalendarController
import app.citti.ui.daily.CalendarScreen
import app.citti.ui.daily.CoreDailyGateway
import app.citti.ui.daily.TodayAction
import app.citti.ui.daily.droppedDaily
import app.citti.ui.image.ImageEnv
import app.citti.ui.image.rememberImagePicker
import app.citti.ui.login.LoginController
import app.citti.ui.login.LoginFlowBackend
import app.citti.ui.login.LoginScreen
import app.citti.ui.nav.DeepLink
import app.citti.ui.nav.DeepLinkTarget
import app.citti.ui.nav.Navigator
import app.citti.ui.nav.Route
import app.citti.ui.nav.entryId
import app.citti.ui.note.ImageViewerScreen
import app.citti.ui.note.NoteActions
import app.citti.ui.note.NoteController
import app.citti.ui.note.NoteScreen
import app.citti.ui.note.dialogActions
import app.citti.ui.search.SearchActions
import app.citti.ui.search.SearchController
import app.citti.ui.search.SearchScreen
import app.citti.ui.settings.AccountInfo
import app.citti.ui.settings.SettingsActions
import app.citti.ui.settings.SettingsController
import app.citti.ui.settings.SettingsScreen
import app.citti.ui.settings.dailyActions
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.background
import app.citti.ui.theme.baseTheme
import app.citti.ui.theme.isDark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Not signed in shows Login. Signed in shows the back stack of [Navigator] (List, Tree, Search, Note, Settings),
 * saved across process death; Login is not a route.
 */
class MainActivity : ComponentActivity() {
    private val container get() = (application as CittiApp).container

    // Controllers live in the Activity-independent scope of the container, one per sign-in session.
    private var loginController: LoginController? = null
    private var browseController: BrowseController? = null

    // One scope per controller, cancelled when the controller is dropped, so no stateIn job outlives it.
    private var browseScope: CoroutineScope? = null

    /** The controllers of the other route entries (Search, Note, Settings), one scope each. */
    private val routes = RouteHost(::newScope, onDrop = { (it as? NoteController)?.onLeave() })

    /** The one snackbar of the app; it outlives a Note that pops itself. */
    private val snackbars = SnackbarState()

    /** [reportFullyDrawn] is called once per Activity, when the first List rows are on screen. */
    private var fullyDrawn = false

    private val titleCollator by lazy { localeTitleCollator() }
    private val pickerFolders by lazy { container.pickerFolders(titleCollator) }
    private val noteCreation by lazy { CoreNoteCreation(container::noteCreator, container.exclusions, Dispatchers.IO) }
    private val rowDates by lazy { JavaTimeRowDateFormatter(zone = container.config.timeZone) }
    private val noteDateTime by lazy { JavaTimeDateTimeFormatter(zone = container.config.timeZone) }
    private val calendarLabels by lazy { JavaTimeCalendarLabels() }

    /** Today's date and the Daily notes of the signed-in account, for the Today button and the Dropped wording. */
    private val dailyGateway by lazy {
        CoreDailyGateway(
            notes = container::dailyNotes,
            today = { localNow().date },
            io = Dispatchers.IO,
            viewLine = { container.noteWidget(it)?.viewLine },
        )
    }

    /** A `citti://` link waiting for the signed-in back stack to exist; consumed once, never re-applied on recreation. */
    private val pendingDeepLink = MutableStateFlow<DeepLinkTarget?>(null)

    // POST_NOTIFICATIONS: asked once, never blocks anything; the sync works without it.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** The theme editor of the open Settings route, for the file picker results. */
    private var themeEditor: ThemeEditorController? = null

    private val exportTheme =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val text = themeEditor?.exportText()
            if (uri != null && text != null) {
                container.appScope.launch(Dispatchers.IO) {
                    val written =
                        runCatching { contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } }.isSuccess
                    if (!written) toastOnMain(R.string.theme_file_export_failed)
                }
            }
        }

    // The result is read up to the cap + 1 byte, so a bigger file is refused without being loaded whole.
    private val importTheme =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                container.appScope.launch(Dispatchers.IO) {
                    val text =
                        runCatching {
                            contentResolver.openInputStream(uri)?.use { it.readCapped(MAX_THEME_FILE_BYTES + 1).toString(Charsets.UTF_8) }
                        }.getOrNull()
                    // A read that failed is an I/O problem, not "Not a citti theme file"; so is an editor that was left meanwhile.
                    val editor = themeEditor
                    if (text == null || editor == null) toastOnMain(R.string.theme_file_import_failed) else editor.importText(text)
                }
            }
        }

    private fun toastOnMain(message: Int) {
        runOnUiThread {
            android.widget.Toast
                .makeText(this, message, android.widget.Toast.LENGTH_LONG)
                .show()
        }
    }

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
        if (shouldApplyDeepLink(isNewIntent = false, hasSavedState = savedInstanceState != null, flags = intent.flags)) {
            pendingDeepLink.value = intent.dataString?.let(DeepLink::parse)
        }
        // One indexed row read before the first frame, so the first frame is in the saved theme (no light flash in Dark).
        val initial = allowingDiskReads { container.appearanceSettings.current() }
        val systemDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        window.setBackgroundDrawable(ColorDrawable(initial.background(systemDark).toArgb()))
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        setContent {
            val appearanceFlow = remember { container.appearanceSettings.flow() }
            val appearance by appearanceFlow.collectAsState(initial)
            val dark = appearance.isDark(isSystemInDarkTheme())
            val windowBg = appearance.background(isSystemInDarkTheme()).toArgb()
            // The bar icons follow the resolved theme, not the phone's.
            LaunchedEffect(dark, windowBg) {
                window.setBackgroundDrawable(ColorDrawable(windowBg))
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
            }
            CittiTheme(appearance) {
                val signedIn by container.signedIn.collectAsState()
                // E2E tests cannot read colors: the resolved theme is written out as "<family>:<light|dark>:<accent>".
                // Debug builds only: a release build gives TalkBack no such node.
                val themeState =
                    if (debuggable) {
                        listOf(
                            appearance.baseTheme().name,
                            if (dark) "dark" else "light",
                            appearance.accent.name,
                        ).joinToString(":").lowercase()
                    } else {
                        null
                    }
                Box(
                    if (themeState == null) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier
                            .fillMaxSize()
                            .testTag(TestTags.THEME_STATE)
                            .semantics { stateDescription = themeState }
                    },
                ) {
                    when (signedIn) {
                        null -> {
                            Blank()
                        }

                        // A link opened while signed out is dropped.
                        false -> {
                            LaunchedEffect(Unit) { pendingDeepLink.value = null }
                            LoginRoute()
                        }

                        true -> {
                            SignedIn()
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (shouldApplyDeepLink(isNewIntent = true, hasSavedState = false, flags = intent.flags)) {
            pendingDeepLink.value = intent.dataString?.let(DeepLink::parse)
        }
    }

    /** Resets the stack to the root and the link's target. The open Notes are flushed first (text and Reading position). */
    private fun applyDeepLink(
        target: DeepLinkTarget,
        navigator: Navigator,
        root: Route,
        today: TodayAction,
    ) {
        routes.controllers<NoteController>().forEach { it.onLeave() }
        when (target) {
            is DeepLinkTarget.OpenNote -> {
                navigator.reset(listOf(root, Route.Note(target.localId, startLine = target.line)))
            }

            is DeepLinkTarget.Today -> {
                navigator.reset(listOf(root))
                today.openToday(resume = target.resume)
            }
        }
    }

    private fun <T> allowingDiskReads(block: () -> T): T {
        val old = StrictMode.allowThreadDiskReads()
        try {
            return block()
        } finally {
            StrictMode.setThreadPolicy(old)
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
        val root = (mode ?: return Blank()).rootRoute()
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
        // With the back stack, not a screen: the Today button, the Calendar and a citti://daily link.
        val today = remember(navigator) { TodayAction(container.appScope, dailyGateway, navigator::push, snackbars::show) }
        // A widget or adb link: after the navigator exists, whether the app was cold-started or was already open.
        LaunchedEffect(navigator) {
            pendingDeepLink.filterNotNull().collect { target ->
                applyDeepLink(target, navigator, root, today)
                pendingDeepLink.value = null
            }
        }
        // The automatic Import from Obsidian, after the first Sync: a snackbar only when it read something.
        LaunchedEffect(Unit) {
            container.dailyImportReports.collect { if (it.readSomething) snackbars.show(Snack.DailyImported) }
        }
        val index = stack.lastIndex
        val route = stack.last()
        Box(Modifier.fillMaxSize()) {
            saved.SaveableStateProvider(entryKey(index, route)) {
                when (route) {
                    is Route.List, is Route.Tree, is Route.Calendar -> BrowseRoute(navigator, index, route, today)
                    Route.Settings -> SettingsRoute(navigator, index)
                    is Route.Search -> SearchRoute(navigator, index, route)
                    is Route.Note -> NoteRoute(navigator, index, route, root)
                    is Route.ImageViewer -> ImageViewerRoute(navigator, route)
                }
            }
            SnackbarHost(
                snackbars,
                onAction = { snack ->
                    when (snack) {
                        Snack.LinkUnsynced, Snack.JournalUnsynced -> navigator.push(Route.Settings)
                        is Snack.LinkMissing -> createFromLink(navigator, snack, today)
                        else -> Unit
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding(),
            )
        }
    }

    /**
     * The Create note action of an unresolved link: the New note dialog of the shown entry, the Note's own, or the
     * List, Tree or Calendar one if the Note has been left since the snackbar came up.
     */
    private fun createFromLink(
        navigator: Navigator,
        snack: Snack.LinkMissing,
        today: TodayAction,
    ) {
        val stack = navigator.stack.value
        val index = stack.lastIndex
        when (val top = stack.last()) {
            is Route.Note -> routes.controller(index, top) { newNoteController(it, top, navigator) }.createFromLink(snack)
            is Route.List, is Route.Tree, is Route.Calendar -> newNoteFlow(navigator, index, top, today).open(snack.folder, snack.title)
            else -> Unit
        }
    }

    /** The New note flow of a List, Tree or Calendar entry (a Calendar entry keeps it with its [CalendarController]). */
    private fun newNoteFlow(
        navigator: Navigator,
        index: Int,
        route: Route,
        today: TodayAction,
    ): NewNoteController =
        if (route is Route.Calendar) {
            calendarEntry(navigator, index, route, today).newNote
        } else {
            routes.controller(index, route) { NewNoteController(it, noteCreation, navigator) }
        }

    private fun calendarEntry(
        navigator: Navigator,
        index: Int,
        route: Route.Calendar,
        today: TodayAction,
    ): CalendarEntry =
        routes.controller(index, route) { scope ->
            CalendarEntry(
                CalendarController(
                    scope = scope,
                    notes = container.dailyEntries(),
                    weekStartSetting = container.dailySettings.configFlow().map { it.weekStart },
                    defaultWeekStart = calendarLabels::defaultWeekStart,
                    journalConflict = container.dailyGuard.conflict(),
                    status = container.syncStatus,
                    today = today,
                    navigator = navigator,
                    route = route,
                    now = ::localNow,
                ),
                NewNoteController(scope, noteCreation, navigator),
            )
        }

    /** The device's local date and time, through the clock and zone seams of [AppConfig]. */
    private fun localNow(): LocalDateTime =
        container.config.clock
            .now()
            .toLocalDateTime(TimeZone.of(container.config.timeZone().id))

    // Today may have changed while the app was in the background: the Calendar outlines the right day.
    override fun onStart() {
        super.onStart()
        routes.controllers<CalendarEntry>().forEach { it.calendar.refreshToday() }
    }

    // The text of an open Note is written when the app leaves the foreground, not only when the screen closes.
    override fun onStop() {
        routes.controllers<NoteController>().forEach { it.onLeave() }
        super.onStop()
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
        index: Int,
        route: Route,
        today: TodayAction,
    ) {
        val controller = remember { browseController ?: newBrowseController().also { browseController = it } }
        val state by controller.state.collectAsState()
        // One New note flow per List, Tree or Calendar entry, so its dialog survives recomposition.
        val newNote = remember(index, route) { newNoteFlow(navigator, index, route, today) }
        val newNoteState by newNote.state.collectAsState()
        val todayBusy by today.busy.collectAsState()
        LaunchedEffect(controller) {
            controller.onShown()
            askNotificationPermissionOnce()
        }
        LaunchedEffect(controller, route) { controller.show(route) }
        // Cold start to the first List rows (M3 spec §11): `adb shell am start -W` and logcat "Fully drawn".
        LaunchedEffect(state.loaded) {
            if (state.loaded && !fullyDrawn) {
                fullyDrawn = true
                withFrameNanos { }
                reportFullyDrawn()
            }
        }
        val view =
            when (route) {
                is Route.Tree -> BrowseView.Tree(route.folder)
                is Route.Calendar -> null
                else -> BrowseView.List(favoritesOnly = (route as? Route.List)?.favoritesOnly ?: false)
            }
        val drawer = rememberCittiDrawerState()
        val scope = rememberCoroutineScope()
        val account by produceState<AccountInfo?>(null) { value = container.accountInfo() }
        // CittiDrawer does not handle Back: an open drawer closes before the stack pops.
        BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
        BackHandler(enabled = newNoteState != null, onBack = newNote::back)
        val fromDrawer: (() -> Unit) -> Unit = { go ->
            scope.launch {
                drawer.close()
                go()
            }
        }
        CittiDrawer(
            state = drawer,
            // Favorites has ← instead of ≡, so it has no drawer.
            gesturesEnabled = view !is BrowseView.List || !view.favoritesOnly,
            drawerContent = {
                AppDrawer(
                    account = account,
                    status = state.status,
                    now = remember(drawer.isOpen) { container.config.nowSeconds() },
                    folders = state.tree?.children.orEmpty(),
                    actions =
                        DrawerActions(
                            onFavorites = { fromDrawer { navigator.reset(listOf(Route.List(), Route.List(favoritesOnly = true))) } },
                            onFolder = { path -> fromDrawer { navigator.reset(breadcrumbPaths(path).map { Route.Tree(it) }) } },
                            onSettings = { fromDrawer { navigator.push(Route.Settings) } },
                        ),
                )
            },
        ) {
            val actions =
                BrowseActions(
                    onOpenMenu = { scope.launch { drawer.open() } },
                    onOpenSearch = { navigator.push(Route.Search()) },
                    onMode = { controller.switchMode(navigator, it) },
                    onOpenFolder = { navigator.push(Route.Tree(it)) },
                    onOpenBreadcrumb = { path ->
                        if (!navigator.popTo(Route.Tree(path))) navigator.reset(breadcrumbPaths(path).map { Route.Tree(it) })
                    },
                    onTreeSort = controller::setSort,
                    onOpenNote = { navigator.push(Route.Note(it)) },
                    onBack = { navigator.pop() },
                    onSyncNow = controller::syncNow,
                    onSignInAgain = { signInAgain() },
                    onNewNote = { newNote.open(newNoteFolder(route)) },
                    onToday = today::openToday,
                )
            if (route is Route.Calendar) {
                CalendarRoute(navigator, index, route, today, state, actions, newNoteBusy = newNoteState?.checking == true)
            } else if (view != null) {
                BrowseScreen(
                    view = view,
                    state = state,
                    nowSeconds = { container.config.nowSeconds() },
                    dates = rowDates,
                    listState = rememberLazyListState(),
                    actions = actions,
                    newNoteBusy = newNoteState?.checking == true,
                    todayBusy = todayBusy != null,
                )
            }
        }
        NewNoteHost(newNoteState, pickerFolders, remember(newNote) { newNote.actions() })
    }

    /** The Calendar mode: its controller lives with the entry, the scaffold's actions come from [BrowseRoute]. */
    @Composable
    private fun CalendarRoute(
        navigator: Navigator,
        index: Int,
        route: Route.Calendar,
        today: TodayAction,
        browse: BrowseUiState,
        actions: BrowseActions,
        newNoteBusy: Boolean,
    ) {
        val controller = remember(index) { calendarEntry(navigator, index, route, today).calendar }
        val state by controller.state.collectAsState()
        BackHandler(enabled = state.confirm != null, onBack = controller::cancelCreate)
        CalendarScreen(
            state = state,
            browse = browse,
            labels = calendarLabels,
            nowSeconds = { container.config.nowSeconds() },
            actions = actions,
            calendar =
                remember(controller) {
                    CalendarActions(
                        onMonthChange = controller::showMonth,
                        onDay = controller::onDay,
                        onRow = controller::onRow,
                        onConfirmCreate = controller::confirmCreate,
                        onCancelCreate = controller::cancelCreate,
                    )
                },
            newNoteBusy = newNoteBusy,
            todayBusy = state.busy != null && state.busy == state.today,
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
            nowSeconds = { container.config.nowSeconds() },
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
                    onOpenNote = { navigator.push(Route.Note(it)) },
                    onBack = { navigator.pop() },
                ),
        )
    }

    /** A Note that no longer exists (deleted by a sync, or gone after process death) pops its entry. */
    @Composable
    private fun NoteRoute(
        navigator: Navigator,
        index: Int,
        route: Route.Note,
        root: Route,
    ) {
        val controller = remember(index) { routes.controller(index, route) { newNoteController(it, route, navigator) } }
        LaunchedEffect(controller, route) { controller.show(route) }
        val state by controller.state.collectAsState()
        LaunchedEffect(state.gone) {
            if (state.gone && navigator.top.entryId == route.entryId && !navigator.pop()) navigator.reset(listOf(root))
        }
        val leave = {
            controller.onLeave()
            navigator.pop()
            Unit
        }
        BackHandler(onBack = leave)
        BackHandler(enabled = state.menuOpen || state.dialog != null, onBack = { controller.back() })
        BackHandler(enabled = state.imageSheet, onBack = controller::dismissImageSheet)
        val picker = rememberImagePicker(container.config::nowMillis, controller::onImagePicked)
        val images = remember(state.folder) { imageEnv(state.folder, controller::onImage) }
        NoteScreen(
            state = state,
            editor = controller.editor,
            previewListState = rememberLazyListState(),
            actions =
                NoteActions(
                    onBack = leave,
                    onTogglePreview = controller::togglePreview,
                    onUndo = controller::undo,
                    onRedo = controller::redo,
                    onToolbar = controller::onToolbar,
                    onTask = controller::onTaskToggle,
                    onLink = controller::onLink,
                    onOpenConflict = controller::openConflict,
                    onSignInAgain = { signInAgain() },
                    onPickImage = { source ->
                        controller.pickImage()
                        picker.launch(source)
                    },
                    onDismissImageSheet = controller::dismissImageSheet,
                    onCancelUpload = controller::cancelUpload,
                    onOpenMenu = controller::openMenu,
                    onMenuItem = controller::onMenu,
                    onDismissMenu = controller::dismissMenu,
                    dialogs = remember(controller) { controller.dialogActions() },
                    onOpenSettings = { navigator.push(Route.Settings) },
                    onDismissNotice = controller::dismissNotice,
                    onViewLine = controller::onViewLine,
                    onOpenNeighbor = controller::openNeighbor,
                ),
            images = images,
            folders = pickerFolders,
            dateTime = noteDateTime::format,
        )
        // "Create note" on an unresolved link: the New note dialog, over the Note.
        controller.newNote?.let { newNote ->
            val newNoteState by newNote.state.collectAsState()
            BackHandler(enabled = newNoteState != null, onBack = newNote::back)
            NewNoteHost(newNoteState, pickerFolders, remember(newNote) { newNote.actions() })
        }
    }

    /** The Images of a Note in [folder]; null while signed out (the Preview then shows the file names). */
    private fun imageEnv(
        folder: String,
        onOpen: (String) -> Unit = {},
    ): ImageEnv? = container.imageResolver()?.let { ImageEnv(container.imageLoader, it, folder, onOpen) }

    @Composable
    private fun ImageViewerRoute(
        navigator: Navigator,
        route: Route.ImageViewer,
    ) {
        val folder by produceState<String?>(null, route.noteLocalId) { value = container.noteFolder(route.noteLocalId) ?: "" }
        val env = folder?.let { f -> remember(f) { imageEnv(f) } }
        if (env != null) ImageViewerScreen(route.link, env, onClose = { navigator.pop() })
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
        val editor by controller.themeEditor.state.collectAsState()
        DisposableEffect(controller) {
            themeEditor = controller.themeEditor
            onDispose { if (themeEditor === controller.themeEditor) themeEditor = null }
        }
        SettingsScreen(
            state = state,
            editor = editor,
            nowSeconds = { container.config.nowSeconds() },
            folders = pickerFolders,
            actions =
                SettingsActions(
                    onBack = { if (!controller.onBack()) navigator.pop() },
                    onInterval = controller::setInterval,
                    onNetwork = controller::setNetwork,
                    onImageCacheMb = controller::setImageCacheMb,
                    onAskClearImages = controller::askClearImages,
                    onSyncNow = controller::syncNow,
                    onOpenPicker = controller::openPicker,
                    onAddFolder = controller::addFolder,
                    onRemoveFolder = controller::removeFolder,
                    onClearFolderError = controller::clearFolderError,
                    onAskSignOut = controller::askSignOut,
                    onSignInAgain = controller::signInAgain,
                    onConfirm = controller::confirm,
                    onDismissConfirm = controller::dismissConfirm,
                    daily = remember(controller) { controller.dailyActions() },
                    appearance =
                        AppearanceActions(
                            onTheme = controller::setTheme,
                            onMode = controller::setMode,
                            onAccent = controller::setAccent,
                            onEditCustom = controller::openThemeEditor,
                        ),
                    editor =
                        remember(
                            controller,
                        ) {
                            controller.themeEditor.actions(
                                onBack = { if (!controller.onBack()) navigator.pop() },
                                onImport = { importTheme.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                                onExport = { exportTheme.launch("citti-theme.json") },
                            )
                        },
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

    private fun newNoteController(
        scope: CoroutineScope,
        route: Route.Note,
        navigator: Navigator,
    ) = NoteController(
        scope = scope,
        leaveScope = container.appScope,
        gateway = container.noteGateway(route.localId),
        status = container.syncStatus,
        route = route,
        navigator = navigator,
        notify = snackbars::show,
        clockMs = container.config::nowMillis,
        imageUpload = container.imageUpload,
        editorContext = Dispatchers.Main.immediate,
        menuGateway = container.noteMenu(route.localId),
        creation = noteCreation,
        droppedDaily = { folder, title -> dailyGateway.droppedDaily(folder, title, calendarLabels) },
        noteNav = { id, folder, title -> container.noteNav(id, folder, title, titleCollator) },
    )

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
            imageCacheMb = container.imageSettings.cacheMbFlow(),
            imageCache = container.imageCache,
            sync = container::sync,
            signOut = {
                container.signOut()
                endSession()
            },
            signInAgain = {
                container.requireSignIn()
                endSession()
            },
            daily = container.dailySettingsStore,
            appearance = container.appearanceStore,
            phoneLanguage = resources.configuration.locales[0].language,
            now = {
                container.config.clock
                    .now()
                    .toLocalDateTime(TimeZone.of(container.config.timeZone().id))
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
            treeSort = container.uiSettings.treeSortFlow(),
            setTreeSort = { withContext(Dispatchers.IO) { container.uiSettings.setTreeSort(it) } },
            collator = titleCollator,
            sync = container::sync,
        )

    private fun openInBrowser(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** A Calendar entry's controllers: the Calendar's own and its New note flow, in the entry's scope. */
    private class CalendarEntry(
        val calendar: CalendarController,
        val newNote: NewNoteController,
    )

    private companion object {
        const val KEY_ASKED_NOTIFICATIONS = "asked-notifications"
    }
}

/** At most [limit] bytes of the stream. */
private fun java.io.InputStream.readCapped(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
