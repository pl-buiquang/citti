package app.citti

import android.content.Context
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.citti.core.api.ApiError
import app.citti.core.api.NotesApi
import app.citti.core.api.NotesApiConfig
import app.citti.core.api.createNotesHttpClient
import app.citti.core.appearance.AppearanceSettings
import app.citti.core.auth.CredentialStore
import app.citti.core.auth.CredentialStoreFactory
import app.citti.core.auth.Credentials
import app.citti.core.auth.LoginFlow
import app.citti.core.auth.prepareForSignIn
import app.citti.core.auth.signOut
import app.citti.core.daily.DailyEntry
import app.citti.core.daily.DailyGuard
import app.citti.core.daily.DailyIndex
import app.citti.core.daily.DailyNeighbor
import app.citti.core.daily.DailyNotes
import app.citti.core.daily.ImportReport
import app.citti.core.daily.LocaleDetector
import app.citti.core.daily.ObsidianDailyImport
import app.citti.core.daily.TemplateSource
import app.citti.core.daily.neighborsOf
import app.citti.core.dav.DavClient
import app.citti.core.dav.DavSetup
import app.citti.core.dav.ImageResolver
import app.citti.core.dav.ImageUploader
import app.citti.core.dav.ObsidianConfig
import app.citti.core.dav.UploadResult
import app.citti.core.db.CittiDatabase
import app.citti.core.note.ExcerptBackfill
import app.citti.core.note.FolderCount
import app.citti.core.note.LinkResolver
import app.citti.core.note.NoteActions
import app.citti.core.note.NoteCreator
import app.citti.core.note.NoteEditor
import app.citti.core.note.Sibling
import app.citti.core.note.siblingsAround
import app.citti.core.search.NoteSearch
import app.citti.core.settings.DailySettings
import app.citti.core.settings.ExclusionKind
import app.citti.core.settings.ExclusionRepository
import app.citti.core.settings.ImageSettings
import app.citti.core.settings.RemoteImages
import app.citti.core.settings.SyncSettingsRepository
import app.citti.core.settings.UiSettings
import app.citti.core.sync.AfterEditPush
import app.citti.core.sync.FolderSync
import app.citti.core.sync.PullSync
import app.citti.core.sync.PushSync
import app.citti.core.sync.SyncEngine
import app.citti.core.sync.SyncEvent
import app.citti.core.sync.SyncOutcome
import app.citti.core.sync.SyncStatus
import app.citti.core.sync.SyncTrigger
import app.citti.ui.browse.NoteRowItem
import app.citti.ui.browse.toRowItem
import app.citti.ui.components.PickerFolders
import app.citti.ui.components.pickerFolders
import app.citti.ui.image.ImageCache
import app.citti.ui.image.ImageUpload
import app.citti.ui.image.VaultImageFetcherFactory
import app.citti.ui.image.VaultImageKeyer
import app.citti.ui.image.seedImageCache
import app.citti.ui.note.CoreNoteMenu
import app.citti.ui.note.NeighborLink
import app.citti.ui.note.NoteGateway
import app.citti.ui.note.NoteMenuGateway
import app.citti.ui.note.NoteNav
import app.citti.ui.settings.AccountInfo
import app.citti.ui.settings.AppearanceStore
import app.citti.ui.settings.CoreAppearanceStore
import app.citti.ui.settings.CoreDailySettingsStore
import app.citti.ui.settings.CoreSettingsStore
import app.citti.ui.settings.DailySettingsStore
import app.citti.widget.DailyWidgetState
import app.citti.widget.FavoriteRow
import app.citti.widget.NoteWidgetRow
import app.citti.widget.WidgetRefresher
import app.citti.widget.shouldRefreshWidgets
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import okio.Path.Companion.toOkioPath
import java.util.Locale

/**
 * The app's object graph, held by [CittiApp]. Everything is built lazily on first use.
 *
 * The database, the credential store and the login client live for the whole process. The sync stack
 * (HTTP client, [NotesApi], [PullSync], [PushSync], [SyncEngine]) depends on the credentials, so it is
 * built after login ([signedIn]) and dropped on [signOut] / [requireSignIn].
 *
 * Background code (WorkManager) calls [syncEngine]: it returns null when nobody is signed in.
 *
 * [config] holds the HTTP engine, the clock and the push delay; the E2E tests replace them.
 */
class AppContainer(
    context: Context,
    val config: AppConfig = AppConfig(),
) {
    private val appContext = context.applicationContext

    /** Process-wide scope for work that must outlive an Activity (login polling, syncs). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val driver by lazy { AndroidSqliteDriver(CittiDatabase.Schema, appContext, "citti.db") }

    val database: CittiDatabase by lazy { CittiDatabase(driver) }

    /** Full-text search over `note_fts` (ADR 0007). */
    val noteSearch: NoteSearch by lazy { NoteSearch(driver, Dispatchers.IO) }

    val credentialStore: CredentialStore by lazy { CredentialStoreFactory(appContext).create() }

    val syncSettings: SyncSettingsRepository by lazy { SyncSettingsRepository(database) }

    /** Redraws the home-screen widgets; every call runs on [appScope]. */
    val widgets: WidgetRefresher by lazy {
        WidgetRefresher(appContext, appScope) {
            withContext(Dispatchers.IO) { dailyNotes()?.let { it.localNote(it.today()) } }
        }
    }

    val exclusions: ExclusionRepository by lazy { ExclusionRepository(database, onChanged = { widgets.updateAll() }) }

    val uiSettings: UiSettings by lazy { UiSettings(database) }

    /** The Appearance (theme, Mode, Accent source, Custom theme); device-local. */
    val appearanceSettings: AppearanceSettings by lazy {
        AppearanceSettings(
            database,
        ).also { it.onChanged = { widgets.appearanceChanged() } }
    }

    val appearanceStore: AppearanceStore by lazy { CoreAppearanceStore(appearanceSettings) }

    val imageSettings: ImageSettings by lazy { ImageSettings(database) }

    /** Settings › Images › Remote images; Ask until the stored choice is read. */
    val remoteImages: StateFlow<RemoteImages> by lazy {
        imageSettings.remoteImagesFlow().stateIn(appScope, SharingStarted.Eagerly, RemoteImages.ASK)
    }

    val dailySettings: DailySettings by lazy { DailySettings(database, exclusions) }

    /** The Journal folder when a state saved before M6 left it under an Unsynced folder. */
    val dailyGuard: DailyGuard by lazy { DailyGuard(dailySettings, exclusions) }

    /** Settings › Daily notes; Import from Obsidian needs the session's WebDAV client. */
    val dailySettingsStore: DailySettingsStore by lazy {
        CoreDailySettingsStore(
            dailySettings,
            dailyGuard,
            exclusions,
            LinkResolver(database),
            { stack.value?.dailyImport },
            Dispatchers.IO,
            onChanged = { widgets.updateDaily() },
        )
    }

    /** The Date locale of Daily notes: the setting, else detected from the Journal folder, else the phone's language. */
    val localeDetector: LocaleDetector by lazy { LocaleDetector(database, dailySettings) { Locale.getDefault().language } }

    /** The Daily notes of the Journal folder by date, for the Calendar; works offline from the local DB. */
    val dailyIndex: DailyIndex by lazy { DailyIndex(database, localeDetector) }

    /** The Calendar's Daily notes by date, following the Journal folder, Date format and Date locale settings. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun dailyEntries(): Flow<Map<LocalDate, DailyEntry>> = dailySettings.configFlow().flatMapLatest(dailyIndex::flow)

    val settingsStore: CoreSettingsStore by lazy { CoreSettingsStore(syncSettings, exclusions, imageSettings) }

    // The Image cache lives in the cache dir (the system may empty it). Its limit is read when the app starts.
    private val imageDiskCache: DiskCache by lazy {
        DiskCache
            .Builder()
            .directory(appContext.cacheDir.resolve("images").toOkioPath())
            .maxSizeBytes(imageSettings.cacheMb() * BYTES_PER_MB)
            .build()
    }

    /** Loads the Images of Notes: Vault files through the WebDAV client and the disk cache, https ones directly. */
    val imageLoader: ImageLoader by lazy {
        ImageLoader
            .Builder(appContext)
            .diskCache(imageDiskCache)
            .components {
                add(VaultImageKeyer())
                add(VaultImageFetcherFactory(::imageResolver, config::nowMillis))
                add(KtorNetworkFetcherFactory(httpClient = { HttpClient(config.httpEngine()) }))
            }.build()
    }

    /** Settings › Images: the size on disk and "Clear image cache". */
    val imageCache: ImageCache =
        object : ImageCache {
            override suspend fun size() = withContext(Dispatchers.IO) { imageDiskCache.size }

            override suspend fun clear() = clearImages()
        }

    private suspend fun clearImages() =
        withContext(Dispatchers.IO) {
            imageLoader.memoryCache?.clear()
            imageDiskCache.clear()
        }

    /** The Folder of a Note, for resolving its Image links; null when the Note is gone. */
    suspend fun noteFolder(localId: Long): String? =
        withContext(Dispatchers.IO) {
            database.noteQueries
                .selectForEditor(localId)
                .executeAsOneOrNull()
                ?.category
        }

    /** Login Flow v2 / manual check. Its client carries no credentials. */
    val loginFlow: LoginFlow by lazy {
        LoginFlow(HttpClient(config.httpEngine()), apiClientFactory = { createNotesHttpClient(config.httpEngine(), it) })
    }

    private class SyncStack(
        val client: HttpClient,
        val engine: SyncEngine,
        val afterEdit: AfterEditPush,
        val dav: DavClient,
        val images: ImageResolver,
        val creator: NoteCreator,
        val uploader: ImageUploader,
        val actions: NoteActions,
        val dailyImport: ObsidianDailyImport,
        val dailyNotes: DailyNotes,
    )

    private val stackLock = Mutex()
    private val stack = MutableStateFlow<SyncStack?>(null)
    private val _signedIn = MutableStateFlow<Boolean?>(null)

    /** Null while the stored credentials are being read, then true (list) or false (login). */
    val signedIn: StateFlow<Boolean?> = _signedIn.asStateFlow()

    /** The status of the current [SyncEngine]; null while signed out. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val syncStatus: Flow<SyncStatus?> = stack.flatMapLatest { s -> s?.engine?.state ?: emptyFlow() }

    private val _dailyImportReports = MutableSharedFlow<ImportReport>(extraBufferCapacity = 1)

    /** The report of the automatic Import from Obsidian, once per sign-in; no replay. */
    val dailyImportReports: SharedFlow<ImportReport> = _dailyImportReports.asSharedFlow()

    init {
        appScope.launch {
            val credentials = withContext(Dispatchers.IO) { credentialStore.load() }
            if (credentials != null) {
                // Records the owner of a database created before the account was remembered; never wipes then.
                withContext(Dispatchers.IO) { prepareForSignIn(database, credentials) }
                buildStack(credentials)
            }
            _signedIn.value = credentials != null
        }
        // Excerpts missing after the migration that added them (normally none); the list fills them in as they land.
        appScope.launch(Dispatchers.IO) {
            val filled = ExcerptBackfill.run(database)
            if (filled > 0) AndroidSyncLog.info("excerpt backfill notes=$filled")
        }
    }

    /**
     * The engine for the current account, built on first call, or null when signed out. Safe to call
     * from any thread and from background workers.
     */
    suspend fun syncEngine(): SyncEngine? {
        stack.value?.let { return it.engine }
        val credentials = withContext(Dispatchers.IO) { credentialStore.load() } ?: return null
        return buildStack(credentials).engine
    }

    /** Runs one sync under a foreground service and a wake lock; no-op when signed out. */
    suspend fun sync(trigger: SyncTrigger) {
        runSync(trigger, ownForeground = true)
    }

    private val _foregroundSync = MutableStateFlow(false)

    /** True while a sync that owns the [SyncForegroundService] runs; the service stops itself once it is false. */
    val foregroundSync: StateFlow<Boolean> = _foregroundSync.asStateFlow()

    /**
     * Runs one sync holding a partial wake lock, so it survives screen-off. With [ownForeground] a
     * [SyncForegroundService] runs for its duration; [SyncWorker] passes false because WorkManager already
     * runs it in the foreground. Returns null when signed out.
     *
     * Only the call that sets [foregroundSync] starts the service, so a second call during a running sync
     * neither starts another service nor ends the first one's. The engine itself answers `AlreadyRunning` to
     * such a call. The service stops itself after the sync (never stopped from here, see [SyncForegroundService]).
     */
    suspend fun runSync(
        trigger: SyncTrigger,
        ownForeground: Boolean,
    ): SyncOutcome? {
        val engine = syncEngine() ?: return null
        val owner = ownForeground && _foregroundSync.compareAndSet(false, true)
        if (owner && !SyncForegroundService.start(appContext)) AndroidSyncLog.warn("sync runs without foreground service")
        try {
            return withSyncWakeLock(appContext) { engine.sync(trigger) }.also {
                importDailySettingsOnce(it)
                if (shouldRefreshWidgets(it)) widgets.updateAll()
            }
        } finally {
            if (owner) _foregroundSync.value = false
        }
    }

    /** After a successful Sync, the automatic Import from Obsidian when `daily.import-done` is not set. */
    private fun importDailySettingsOnce(outcome: SyncOutcome) {
        if (outcome !is SyncOutcome.Synced && outcome != SyncOutcome.NothingChanged) return
        val import = stack.value?.dailyImport ?: return
        appScope.launch(Dispatchers.IO) {
            if (dailySettings.importDone()) return@launch
            import.runOnce()?.let {
                AndroidSyncLog.info("daily import read=${it.read.keys} refused=${it.refused.map { r -> r.key }} notFound=${it.notFound}")
                _dailyImportReports.emit(it)
                widgets.updateDaily()
            }
        }
    }

    /** Saves the credentials after a successful login and (re)builds the sync stack. */
    suspend fun onSignedIn(credentials: Credentials) {
        // A database that belongs to another server or user is wiped first, so its Notes never mix with
        // (or get pushed to) the new account. Same account: local data and queued edits stay.
        dropStack()
        withContext(Dispatchers.IO) {
            prepareForSignIn(database, credentials)
            credentialStore.save(credentials)
        }
        buildStack(credentials)
        _signedIn.value = true
        widgets.updateAll()
    }

    /** Wipes credentials and local data (core [signOut]) and drops the sync stack. */
    suspend fun signOut() {
        dropStack()
        withContext(Dispatchers.IO) {
            signOut(credentialStore, database)
            // Deleted rows leave their text in free pages of the file; VACUUM rewrites it without them.
            runCatching { driver.execute(null, "VACUUM", 0) }
            clearCameraShots(appContext.cacheDir)
        }
        clearImages()
        _signedIn.value = false
        widgets.updateAll()
    }

    /**
     * Back to the login screen after an auth failure, keeping local data and queued edits. The next
     * [onSignedIn] replaces the credentials.
     */
    suspend fun requireSignIn() {
        dropStack()
        _signedIn.value = false
        widgets.updateAll()
    }

    /** Server host and user of the stored credentials, or null when signed out. */
    suspend fun accountInfo(): AccountInfo? {
        val c = withContext(Dispatchers.IO) { credentialStore.load() } ?: return null
        val host = c.serverUrl.substringAfter("://").substringBefore('/')
        return AccountInfo(host, c.user)
    }

    /** List mode rows (favorites first, then newest), kept current by SQLDelight. */
    fun listRows(favoritesOnly: Boolean): Flow<List<NoteRowItem>> =
        database.noteQueries
            .selectListRows(if (favoritesOnly) 1L else 0L)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map { it.toRowItem() } }

    /** Favorites widget rows (the Drawer > Favorites set, newest first); one-shot, the widget redraws on update. */
    suspend fun favoriteWidgetRows(): List<FavoriteRow> =
        withContext(Dispatchers.IO) {
            database.noteQueries
                .selectFavoriteWidgetRows()
                .executeAsList()
                .map { FavoriteRow(it.local_id, it.title, it.category) }
        }

    /** The row a Note widget draws, or null when the Note is gone. */
    suspend fun noteWidget(localId: Long): NoteWidgetRow? =
        withContext(Dispatchers.IO) {
            database.noteQueries
                .selectNoteWidget(localId)
                .executeAsOneOrNull()
                ?.let { NoteWidgetRow(it.title, it.category, it.content, it.view_line?.toInt()) }
        }

    /** The Reading position of a Note, written when its screen is left. Sync never writes it. */
    suspend fun setViewLine(
        localId: Long,
        line: Int,
    ) {
        withContext(Dispatchers.IO) { database.noteQueries.setViewLine(line.toLong(), localId) }
        widgets.updateNote(localId)
    }

    /** What the Daily note widget shows for [date], through M6's local lookup (Hidden notes included). */
    suspend fun dailyWidget(date: LocalDate): DailyWidgetState =
        withContext(Dispatchers.IO) {
            if (dailyGuard.current() != null) return@withContext DailyWidgetState.Guard
            val id = dailyNotes()?.localNote(date) ?: return@withContext DailyWidgetState.Missing
            val row = noteWidget(id) ?: return@withContext DailyWidgetState.Missing
            DailyWidgetState.Found(id, row)
        }

    /** Visible Notes per Folder, for the Tree mode tree. */
    fun folderCounts(): Flow<List<FolderCount>> =
        database.noteQueries
            .countVisibleByFolder()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map { FolderCount(it.category, it.notes, it.newest ?: 0L) } }

    /** The visible Notes directly in [path], unsorted (BrowseController sorts them). */
    fun folderNotes(path: String): Flow<List<NoteRowItem>> =
        database.noteQueries
            .selectFolderNotes(path)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map { it.toRowItem() } }

    /** Notes per Folder, Hidden ones included, with the exclusions: what the Folder picker lists. */
    fun pickerFolders(collator: Comparator<String>): Flow<PickerFolders> =
        combine(
            database.noteQueries
                .countByFolder()
                .asFlow()
                .mapToList(Dispatchers.IO)
                .map { rows -> rows.map { it.category to it.notes } },
            exclusions.flow(ExclusionKind.HIDDEN),
            exclusions.flow(ExclusionKind.UNSYNCED),
        ) { counts, hidden, unsynced -> pickerFolders(counts, hidden, unsynced, collator) }

    /** Every Note creation goes through it (ADR 0006); null while signed out. */
    fun noteCreator(): NoteCreator? = stack.value?.creator

    /** Today and the Calendar open or create Daily notes through it; null while signed out. */
    fun dailyNotes(): DailyNotes? = stack.value?.dailyNotes

    /** Favorite, Rename, Move, Delete and Note info of the ⋮ menu; null while signed out. */
    fun noteActions(): NoteActions? = stack.value?.actions

    /** The ⋮ menu of one open Note for its [app.citti.ui.note.NoteController]. */
    fun noteMenu(localId: Long): NoteMenuGateway = CoreNoteMenu(localId, ::noteActions, exclusions, Dispatchers.IO)

    /**
     * The Notes around the Note [localId] of [folder] and [title], for the bar at the bottom of its screen: the
     * Daily notes before and after a Daily note (empty days skipped), else the Notes before and after it in its Folder,
     * ordered as Tree mode does ([collator] and the Tree sort setting).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun noteNav(
        localId: Long,
        folder: String,
        title: String,
        collator: Comparator<String>,
    ): Flow<NoteNav?> =
        dailySettings
            .configFlow()
            .flatMapLatest { config ->
                val date = dailyNotes()?.dateOf(folder, title, config)
                if (date == null) {
                    siblingNav(localId, folder, collator)
                } else {
                    dailyIndex.flow(config).map { entries ->
                        val around = entries.neighborsOf(date)

                        fun link(n: DailyNeighbor) =
                            database.noteQueries
                                .selectByLocalId(n.localId)
                                .executeAsOneOrNull()
                                ?.let { NeighborLink(n.localId, it.title) }
                        NoteNav(around.previous?.let(::link), around.next?.let(::link))
                    }
                }
            }.flowOn(Dispatchers.IO)

    private fun siblingNav(
        localId: Long,
        folder: String,
        collator: Comparator<String>,
    ): Flow<NoteNav?> =
        flow {
            val hidden =
                database.noteQueries
                    .selectByLocalId(localId)
                    .executeAsOneOrNull()
                    ?.hidden ?: return@flow emit(null)
            emitAll(
                combine(
                    database.noteQueries
                        .selectSiblings(folder, hidden)
                        .asFlow()
                        .mapToList(Dispatchers.IO),
                    uiSettings.treeSortFlow(),
                ) { rows, sort ->
                    val around = siblingsAround(rows.map { Sibling(it.local_id, it.title, it.modified) }, localId, sort, collator)
                    NoteNav(
                        around.previous?.let { NeighborLink(it.localId, it.title) },
                        around.next?.let { NeighborLink(it.localId, it.title) },
                    )
                },
            )
        }

    /** The data of one open Note for its [app.citti.ui.note.NoteController]. */
    fun noteGateway(localId: Long): NoteGateway =
        DbNoteGateway(
            database,
            localId,
            NoteEditor(database, localId, config::nowSeconds, ::scheduleAfterEditPush),
            LinkResolver(database),
            fetchContent = { (syncEngine()?.fetchContent(localId) ?: false).also { if (it) widgets.updateNote(localId) } },
            onViewLine = { widgets.updateNote(localId) },
            dropped = {
                flow {
                    val engine = syncEngine() ?: return@flow
                    emitAll(
                        engine.events
                            .filterIsInstance<SyncEvent.Dropped>()
                            .filter { it.localId == localId }
                            .map { it.serverLocalId },
                    )
                }
            },
        )

    private suspend fun buildStack(credentials: Credentials): SyncStack =
        stackLock.withLock {
            stack.value?.client?.close()
            val apiConfig = NotesApiConfig(credentials.serverUrl, credentials.user, credentials.appPassword)
            val client = createNotesHttpClient(config.httpEngine(), apiConfig)
            val api = NotesApi(client, apiConfig, AndroidSyncLog)
            val davSetup = DavSetup(api, database, credentials.serverUrl)
            val now = config::nowSeconds
            // SyncEngine reads settings in its constructor, so build it off the main thread.
            val engine =
                withContext(Dispatchers.IO) {
                    SyncEngine(
                        PushSync(api, database, AndroidSyncLog, now),
                        PullSync(api, database, log = AndroidSyncLog),
                        database,
                        AndroidSyncLog,
                        api.stats,
                        dav = davSetup,
                        folderSync = FolderSync(database, api, exclusions, AndroidSyncLog),
                        now = now,
                    )
                }
            val dav = DavClient(client, { davSetup.root() }, AndroidSyncLog, api.stats)
            val obsidian = ObsidianConfig(dav, database)
            val images = ImageResolver(dav, obsidian)
            val uploader = ImageUploader(dav, obsidian, imageSettings, images)
            val afterEdit = AfterEditPush(appScope, engine, config.afterEditPushDelay.inWholeMilliseconds)
            val creator = NoteCreator(database, engine, appScope, exclusions, afterEdit::schedule, now)
            val actions = NoteActions(database, creator, exclusions, afterEdit::schedule, now, onChanged = { widgets.updateAll() })
            val dailyImport = ObsidianDailyImport(dav, dailySettings)
            val templates = TemplateSource(database, api, dav, dailySettings, engine::fetchContent, appScope, AndroidSyncLog)
            val dailyNotes =
                DailyNotes(
                    database,
                    dailySettings,
                    creator,
                    templates,
                    localeDetector,
                    { id -> engine.fetchContent(id).also { if (it) widgets.updateNote(id) } },
                    exclusions,
                    appScope,
                    clock = config.clock::now,
                    zone = { TimeZone.of(config.timeZone().id) },
                    onCreated = { widgets.updateDaily() },
                )
            SyncStack(client, engine, afterEdit, dav, images, creator, uploader, actions, dailyImport, dailyNotes)
                .also { stack.value = it }
        }

    /** No sync is running and no push after an edit is pending (signed out counts as idle). For the E2E idling resource. */
    fun isSyncIdle(): Boolean {
        val s = stack.value ?: return true
        return s.engine.state.value !is SyncStatus.Running && !s.afterEdit.isPending
    }

    /** Image links of Notes (M4 UI). Null while signed out. */
    fun imageResolver(): ImageResolver? = stack.value?.images

    /**
     * Insert image: uploads into the Vault with the current account and seeds the Image cache with the bytes, so the
     * new link shows at once. Signed out: [ApiError.DavNotReady] (shown as offline).
     */
    val imageUpload =
        ImageUpload { image, noteFolder ->
            val uploader = stack.value?.uploader ?: return@ImageUpload UploadResult.Failed(ApiError.DavNotReady)
            withContext(Dispatchers.IO) {
                // The settings and the Obsidian config are read from the database.
                val result = uploader.upload(image.bytes, image.name, image.mimeType, noteFolder)
                if (result is UploadResult.Uploaded) {
                    seedImageCache(imageDiskCache, result.path, image.bytes, result.etag, image.mimeType, config.nowMillis())
                }
                result
            }
        }

    /** Called by the editor after each successful save; pushes about 2 s after the last one. No-op while signed out. */
    fun scheduleAfterEditPush() {
        stack.value?.afterEdit?.schedule()
    }

    private suspend fun dropStack() {
        stackLock.withLock {
            stack.value?.afterEdit?.cancel()
            stack.value?.client?.close()
            stack.value = null
        }
    }

    private companion object {
        const val BYTES_PER_MB = 1024L * 1024L
    }
}
