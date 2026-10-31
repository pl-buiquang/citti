package app.citti

import android.content.Context
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.citti.core.api.NotesApi
import app.citti.core.api.NotesApiConfig
import app.citti.core.api.createNotesHttpClient
import app.citti.core.api.defaultHttpEngine
import app.citti.core.auth.CredentialStore
import app.citti.core.auth.CredentialStoreFactory
import app.citti.core.auth.Credentials
import app.citti.core.auth.LoginFlow
import app.citti.core.auth.prepareForSignIn
import app.citti.core.auth.signOut
import app.citti.core.db.CittiDatabase
import app.citti.core.note.ExcerptBackfill
import app.citti.core.settings.ExclusionRepository
import app.citti.core.settings.SyncSettingsRepository
import app.citti.core.settings.UiSettings
import app.citti.core.sync.PullSync
import app.citti.core.sync.PushSync
import app.citti.core.sync.SyncEngine
import app.citti.core.sync.SyncOutcome
import app.citti.core.sync.SyncStatus
import app.citti.core.sync.SyncTrigger
import app.citti.ui.browse.NoteRowItem
import app.citti.ui.browse.toRowItem
import app.citti.ui.settings.AccountInfo
import app.citti.ui.settings.CoreSettingsStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app's object graph, held by [CittiApp]. Everything is built lazily on first use.
 *
 * The database, the credential store and the login client live for the whole process. The sync stack
 * (HTTP client, [NotesApi], [PullSync], [PushSync], [SyncEngine]) depends on the credentials, so it is
 * built after login ([signedIn]) and dropped on [signOut] / [requireSignIn].
 *
 * Background code (WorkManager) calls [syncEngine]: it returns null when nobody is signed in.
 */
class AppContainer(
    context: Context,
) {
    private val appContext = context.applicationContext

    /** Process-wide scope for work that must outlive an Activity (login polling, syncs). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: CittiDatabase by lazy {
        CittiDatabase(AndroidSqliteDriver(CittiDatabase.Schema, appContext, "citti.db"))
    }

    val credentialStore: CredentialStore by lazy { CredentialStoreFactory(appContext).create() }

    val syncSettings: SyncSettingsRepository by lazy { SyncSettingsRepository(database) }

    val exclusions: ExclusionRepository by lazy { ExclusionRepository(database) }

    val uiSettings: UiSettings by lazy { UiSettings(database) }

    val settingsStore: CoreSettingsStore by lazy { CoreSettingsStore(syncSettings, exclusions) }

    /** Login Flow v2 / manual check. Its client carries no credentials. */
    val loginFlow: LoginFlow by lazy { LoginFlow(HttpClient(defaultHttpEngine())) }

    private class SyncStack(
        val client: HttpClient,
        val engine: SyncEngine,
    )

    private val stackLock = Mutex()
    private val stack = MutableStateFlow<SyncStack?>(null)
    private val _signedIn = MutableStateFlow<Boolean?>(null)

    /** Null while the stored credentials are being read, then true (list) or false (login). */
    val signedIn: StateFlow<Boolean?> = _signedIn.asStateFlow()

    /** The status of the current [SyncEngine]; null while signed out. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val syncStatus: Flow<SyncStatus?> = stack.flatMapLatest { s -> s?.engine?.state ?: emptyFlow() }

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

    private val syncGuarded = AtomicBoolean(false)

    /**
     * Runs one sync holding a partial wake lock, so it survives screen-off. With [ownForeground] a
     * [SyncForegroundService] runs for its duration; [SyncWorker] passes false because WorkManager already
     * runs it in the foreground. Returns null when signed out.
     *
     * Only the call that wins [syncGuarded] starts (and later stops) the service, so a second call during a
     * running sync neither starts another service nor stops the first one's. The engine itself answers
     * `AlreadyRunning` to such a call.
     */
    suspend fun runSync(
        trigger: SyncTrigger,
        ownForeground: Boolean,
    ): SyncOutcome? {
        val engine = syncEngine() ?: return null
        val owner = ownForeground && syncGuarded.compareAndSet(false, true)
        if (owner && !SyncForegroundService.start(appContext)) AndroidSyncLog.warn("sync runs without foreground service")
        try {
            return withSyncWakeLock(appContext) { engine.sync(trigger) }
        } finally {
            if (owner) {
                SyncForegroundService.stop(appContext)
                syncGuarded.set(false)
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
    }

    /** Wipes credentials and local data (core [signOut]) and drops the sync stack. */
    suspend fun signOut() {
        dropStack()
        withContext(Dispatchers.IO) { signOut(credentialStore, database) }
        _signedIn.value = false
    }

    /**
     * Back to the login screen after an auth failure, keeping local data and queued edits. The next
     * [onSignedIn] replaces the credentials.
     */
    suspend fun requireSignIn() {
        dropStack()
        _signedIn.value = false
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

    /** Visible Notes per Folder, for the Tree mode tree. */
    fun folderCounts(): Flow<List<Pair<String, Long>>> =
        database.noteQueries
            .countVisibleByFolder()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map { it.category to it.notes } }

    /** The visible Notes directly in [path], unsorted (BrowseController sorts them). */
    fun folderNotes(path: String): Flow<List<NoteRowItem>> =
        database.noteQueries
            .selectFolderNotes(path)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map { it.toRowItem() } }

    private suspend fun buildStack(credentials: Credentials): SyncStack =
        stackLock.withLock {
            stack.value?.client?.close()
            val config = NotesApiConfig(credentials.serverUrl, credentials.user, credentials.appPassword)
            val client = createNotesHttpClient(config)
            val api = NotesApi(client, config, AndroidSyncLog)
            val now = { System.currentTimeMillis() / 1000 }
            // SyncEngine reads settings in its constructor, so build it off the main thread.
            val engine =
                withContext(Dispatchers.IO) {
                    SyncEngine(
                        PushSync(api, database, AndroidSyncLog, now),
                        PullSync(api, database, log = AndroidSyncLog),
                        database,
                        AndroidSyncLog,
                        api.stats,
                        now = now,
                    )
                }
            SyncStack(client, engine).also { stack.value = it }
        }

    private suspend fun dropStack() {
        stackLock.withLock {
            stack.value?.client?.close()
            stack.value = null
        }
    }
}
