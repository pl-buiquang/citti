package app.citti.ui.settings

import app.citti.core.settings.ExclusionKind
import app.citti.core.settings.ExclusionRepository
import app.citti.core.settings.SyncNetwork
import app.citti.core.settings.SyncSettingsRepository
import app.citti.core.settings.normalizeFolderPath
import app.citti.core.sync.SyncStatus
import app.citti.core.sync.SyncTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Server host and user shown under Account. */
data class AccountInfo(
    val host: String,
    val user: String,
)

/** What the controller changes; [CoreSettingsStore] adapts core's repositories, tests use a fake. */
interface SettingsStore {
    fun setIntervalMinutes(minutes: Int)

    fun setNetwork(network: SyncNetwork)

    /** Returns the stored path, or null when blank or already listed. */
    fun addExclusion(
        kind: ExclusionKind,
        path: String,
    ): String?

    fun removeExclusion(
        kind: ExclusionKind,
        path: String,
    )

    fun notesUnder(path: String): Int

    fun pendingUnder(path: String): Int
}

class CoreSettingsStore(
    private val sync: SyncSettingsRepository,
    private val exclusions: ExclusionRepository,
) : SettingsStore {
    override fun setIntervalMinutes(minutes: Int) = sync.setIntervalMinutes(minutes)

    override fun setNetwork(network: SyncNetwork) {
        sync.setNetwork(network)
    }

    override fun addExclusion(
        kind: ExclusionKind,
        path: String,
    ) = exclusions.add(kind, path)

    override fun removeExclusion(
        kind: ExclusionKind,
        path: String,
    ) {
        exclusions.remove(kind, path)
    }

    override fun notesUnder(path: String) = exclusions.notesUnder(path)

    override fun pendingUnder(path: String) = exclusions.pendingUnder(path)
}

/** Where Settings is: the home page or the folder picker for one list. */
sealed interface SettingsPage {
    data object Home : SettingsPage

    data class PickFolder(
        val kind: ExclusionKind,
    ) : SettingsPage
}

/** A dialog waiting for the user's answer. */
sealed interface SettingsConfirm {
    data object SignOut : SettingsConfirm

    /** [notes] local Notes are deleted from the phone; [pending] of them have edits and are kept. */
    data class AddUnsynced(
        val path: String,
        val notes: Int,
        val pending: Int,
    ) : SettingsConfirm
}

enum class FolderError { Blank, AlreadyListed }

data class SettingsUiState(
    val account: AccountInfo,
    val page: SettingsPage = SettingsPage.Home,
    val intervalMinutes: Int = 30,
    val network: SyncNetwork = SyncNetwork.ANY,
    val status: SyncStatus? = null,
    val unsynced: List<String> = emptyList(),
    val hidden: List<String> = emptyList(),
    val knownFolders: List<String> = emptyList(),
    val confirm: SettingsConfirm? = null,
    val folderError: FolderError? = null,
) {
    val syncing: Boolean get() = status is SyncStatus.Running

    fun listOf(kind: ExclusionKind): List<String> = if (kind == ExclusionKind.UNSYNCED) unsynced else hidden
}

private data class Local(
    val page: SettingsPage = SettingsPage.Home,
    val confirm: SettingsConfirm? = null,
    val folderError: FolderError? = null,
)

/**
 * State holder of Settings (Account, Sync, Exclusions). Every exclusion change triggers a manual sync, which
 * makes the pull notice the changed lists (it skips the 304 check and applies them).
 *
 * [signOut] wipes credentials and local data; [signInAgain] keeps local data. Both are supplied by the app.
 */
class SettingsController(
    private val scope: CoroutineScope,
    account: AccountInfo,
    private val store: SettingsStore,
    interval: Flow<Int>,
    network: Flow<SyncNetwork>,
    status: Flow<SyncStatus?>,
    unsynced: Flow<List<String>>,
    hidden: Flow<List<String>>,
    knownFolders: Flow<List<String>>,
    private val sync: suspend (SyncTrigger) -> Unit,
    private val signOut: suspend () -> Unit,
    private val signInAgain: suspend () -> Unit,
) {
    private val local = MutableStateFlow(Local())

    val state: StateFlow<SettingsUiState> =
        combine(
            combine(interval, network, status) { i, n, s -> Triple(i, n, s) },
            combine(unsynced, hidden, knownFolders) { u, h, k -> Triple(u, h, k) },
            local,
        ) { (i, n, s), (u, h, k), l ->
            SettingsUiState(account, l.page, i, n, s, u, h, k, l.confirm, l.folderError)
        }.stateIn(scope, SharingStarted.Eagerly, SettingsUiState(account))

    /** Handles the system Back: returns true when it was consumed (closes a dialog or the picker). */
    fun onBack(): Boolean {
        val l = local.value
        return when {
            l.confirm != null -> {
                local.value = l.copy(confirm = null)
                true
            }

            l.page != SettingsPage.Home -> {
                local.value = Local()
                true
            }

            else -> {
                false
            }
        }
    }

    fun setInterval(minutes: Int) = store.setIntervalMinutes(minutes)

    fun setNetwork(network: SyncNetwork) = store.setNetwork(network)

    fun syncNow() {
        scope.launch { sync(SyncTrigger.Manual) }
    }

    fun openPicker(kind: ExclusionKind) {
        local.value = Local(page = SettingsPage.PickFolder(kind))
    }

    fun clearFolderError() = local.update { it.copy(folderError = null) }

    /** A folder chosen from the picker or typed by hand. Unsynced folders with local Notes ask first. */
    fun addFolder(
        kind: ExclusionKind,
        path: String,
    ) {
        val p = normalizeFolderPath(path)
        if (p == null) {
            local.update { it.copy(folderError = FolderError.Blank) }
            return
        }
        if (p in state.value.listOf(kind)) {
            local.update { it.copy(folderError = FolderError.AlreadyListed) }
            return
        }
        val notes = if (kind == ExclusionKind.UNSYNCED) store.notesUnder(p) else 0
        if (notes > 0) {
            local.update { it.copy(confirm = SettingsConfirm.AddUnsynced(p, notes, store.pendingUnder(p)), folderError = null) }
        } else {
            apply(kind, p)
        }
    }

    fun removeFolder(
        kind: ExclusionKind,
        path: String,
    ) {
        store.removeExclusion(kind, path)
        syncAfterChange()
    }

    fun confirm() {
        when (val c = local.value.confirm) {
            is SettingsConfirm.AddUnsynced -> {
                apply(ExclusionKind.UNSYNCED, c.path)
            }

            SettingsConfirm.SignOut -> {
                local.update { it.copy(confirm = null) }
                scope.launch { signOut() }
            }

            null -> {
                Unit
            }
        }
    }

    fun dismissConfirm() = local.update { it.copy(confirm = null) }

    fun askSignOut() = local.update { it.copy(confirm = SettingsConfirm.SignOut) }

    fun signInAgain() {
        scope.launch { signInAgain.invoke() }
    }

    private fun apply(
        kind: ExclusionKind,
        path: String,
    ) {
        store.addExclusion(kind, path)
        local.value = Local()
        syncAfterChange()
    }

    private fun syncAfterChange() {
        scope.launch { sync(SyncTrigger.Manual) }
    }
}
