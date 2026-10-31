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
import app.citti.core.settings.ExclusionRepository
import app.citti.core.settings.SyncSettingsRepository
import app.citti.core.sync.PullSync
import app.citti.core.sync.PushSync
import app.citti.core.sync.SyncEngine
import app.citti.core.sync.SyncStatus
import app.citti.core.sync.SyncTrigger
import app.citti.ui.list.NoteListItem
import app.citti.ui.list.toListItem
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

    /** Runs one sync; no-op when signed out. */
    suspend fun sync(trigger: SyncTrigger) {
        syncEngine()?.sync(trigger)
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

    /** Notes for the debug list, kept current by SQLDelight. */
    fun noteListItems(): Flow<List<NoteListItem>> =
        database.noteQueries
            .selectAllForList()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { notes -> notes.map { it.toListItem() } }

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
                        now,
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
