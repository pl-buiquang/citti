package app.citti.ui.settings

import app.citti.core.appearance.AccentSource
import app.citti.core.appearance.Appearance
import app.citti.core.appearance.ModeChoice
import app.citti.core.appearance.ThemeChoice
import app.citti.core.daily.DateLocales
import app.citti.core.daily.ImportReport
import app.citti.core.daily.MomentFormat
import app.citti.core.note.NameError
import app.citti.core.note.Suggestion
import app.citti.core.settings.AddResult
import app.citti.core.settings.DailyConfig
import app.citti.core.settings.ExclusionKind
import app.citti.core.settings.ExclusionRepository
import app.citti.core.settings.FolderResult
import app.citti.core.settings.FormatResult
import app.citti.core.settings.ImageSettings
import app.citti.core.settings.SyncNetwork
import app.citti.core.settings.SyncSettingsRepository
import app.citti.core.settings.normalizeFolderPath
import app.citti.core.sync.SyncStatus
import app.citti.core.sync.SyncTrigger
import app.citti.ui.appearance.ThemeEditorController
import app.citti.ui.components.FolderPickerState
import app.citti.ui.image.ImageCache
import app.citti.ui.theme.baseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime

/** Server host and user shown under Account. */
data class AccountInfo(
    val host: String,
    val user: String,
)

/** What the controller changes; [CoreSettingsStore] adapts core's repositories, tests use a fake. */
interface SettingsStore {
    fun setIntervalMinutes(minutes: Int)

    fun setNetwork(network: SyncNetwork)

    fun setImageCacheMb(mb: Int)

    /** Returns [AddResult.Added] with the stored path, or why the folder was refused. */
    fun addExclusion(
        kind: ExclusionKind,
        path: String,
    ): AddResult

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
    private val images: ImageSettings,
) : SettingsStore {
    override fun setIntervalMinutes(minutes: Int) = sync.setIntervalMinutes(minutes)

    override fun setNetwork(network: SyncNetwork) {
        sync.setNetwork(network)
    }

    override fun setImageCacheMb(mb: Int) = images.setCacheMb(mb)

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

/** Where Settings is: the home page, the folder picker for one list, or a picker of Settings › Daily notes. */
sealed interface SettingsPage {
    data object Home : SettingsPage

    data class PickFolder(
        val kind: ExclusionKind,
    ) : SettingsPage

    /** The Journal folder picker; [error] is why Choose was refused for [FolderPickerState.path]. */
    data class PickJournal(
        val picker: FolderPickerState,
        val error: NameError? = null,
    ) : SettingsPage

    /** The Template picker: a search over the local Notes ([suggestions] for [query]) plus "None". */
    data class PickTemplate(
        val query: String = "",
        val suggestions: List<Suggestion> = emptyList(),
    ) : SettingsPage

    /** The theme editor (Settings › Appearance › Edit custom theme). */
    data object ThemeEditor : SettingsPage
}

/** A dialog waiting for the user's answer. */
sealed interface SettingsConfirm {
    data object SignOut : SettingsConfirm

    /** Empties the Image cache; nothing but the cached files is touched. */
    data object ClearImages : SettingsConfirm

    /** [notes] local Notes are deleted from the phone; [pending] of them have edits and are kept. */
    data class AddUnsynced(
        val path: String,
        val notes: Int,
        val pending: Int,
    ) : SettingsConfirm
}

/** Why a folder typed or picked for an exclusion list was refused. */
sealed interface FolderError {
    data object Blank : FolderError

    data object AlreadyListed : FolderError

    /** The Journal folder [journal] is this folder or lies below it. */
    data class JournalFolder(
        val journal: String,
    ) : FolderError
}

/** Settings › Daily notes. */
data class DailyUiState(
    val config: DailyConfig = DailyConfig(),
    /** The Journal folder while it lies under an Unsynced folder: the guard banner. */
    val conflict: String? = null,
    /** False when the Template path isn't a Note on this phone ("Not synced; fetched when needed"). */
    val templateLocal: Boolean = true,
    /** The Date format field: what was typed, or the saved format. */
    val format: String = config.format,
    /** Why the typed format was not saved; the saved one stays in use. */
    val formatError: FormatResult? = null,
    /** Today in the typed format (the saved one while the field is blank), with the Date locale in use. */
    val formatExample: String = "",
    /** The Date locale Templates render with now. */
    val localeInUse: String = DateLocales.en.code,
    /** `{{date:MMMM}}` for today per Date locale choice: null is Automatic, then each code. */
    val monthExamples: Map<String?, String> = emptyMap(),
    /** Import from Obsidian is running. */
    val importing: Boolean = false,
    /** The report dialog of the last Import from Obsidian, until dismissed. */
    val importReport: ImportReport? = null,
)

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
    /** The Image cache limit in megabytes, and the bytes in use now. */
    val imageCacheMb: Int = 500,
    val imageCacheBytes: Long = 0,
    val daily: DailyUiState = DailyUiState(),
    val appearance: Appearance = Appearance(),
) {
    val syncing: Boolean get() = status is SyncStatus.Running

    fun listOf(kind: ExclusionKind): List<String> = if (kind == ExclusionKind.UNSYNCED) unsynced else hidden
}

private data class Lists(
    val unsynced: List<String>,
    val hidden: List<String>,
    val known: List<String>,
    val imageMb: Int,
)

private data class Local(
    val page: SettingsPage = SettingsPage.Home,
    val confirm: SettingsConfirm? = null,
    val folderError: FolderError? = null,
    val imageBytes: Long = 0,
)

private data class DailyLocal(
    val formatDraft: String? = null,
    val formatError: FormatResult? = null,
    val importing: Boolean = false,
    val importReport: ImportReport? = null,
)

/**
 * State holder of Settings (Account, Sync, Exclusions, Images, Daily notes). Every exclusion change triggers a
 * manual sync, which makes the pull notice the changed lists (it skips the 304 check and applies them).
 *
 * Daily notes: [phoneLanguage] is the phone's language code (the Date locale fallback) and [now] the local date and
 * time the live examples are written for.
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
    imageCacheMb: Flow<Int>,
    private val imageCache: ImageCache,
    private val sync: suspend (SyncTrigger) -> Unit,
    private val signOut: suspend () -> Unit,
    private val signInAgain: suspend () -> Unit,
    private val daily: DailySettingsStore,
    private val appearance: AppearanceStore,
    private val phoneLanguage: String,
    private val now: () -> LocalDateTime,
) {
    private val local = MutableStateFlow(Local())

    /** The theme editor's state holder; it starts afresh every time the editor opens. */
    val themeEditor = ThemeEditorController(scope, appearance)
    private val dailyLocal = MutableStateFlow(DailyLocal())

    // Each typed Date format is saved in order, so the last one typed is the one kept.
    private val formatLock = Mutex()

    private val dailyState: Flow<DailyUiState> =
        combine(
            daily.config().map { c -> c to (c.template.isEmpty() || daily.isLocalNote(c.template)) },
            daily.conflict(),
            dailyLocal,
        ) { (config, templateLocal), conflict, l -> dailyUiState(config, conflict, templateLocal, l) }

    val state: StateFlow<SettingsUiState> =
        combine(
            combine(interval, network, status) { i, n, s -> Triple(i, n, s) },
            combine(unsynced, hidden, knownFolders, imageCacheMb) { u, h, k, m -> Lists(u, h, k, m) },
            local,
            combine(dailyState, appearance.appearance()) { d, a -> d to a },
        ) { (i, n, s), lists, l, (d, a) ->
            SettingsUiState(
                account,
                l.page,
                i,
                n,
                s,
                lists.unsynced,
                lists.hidden,
                lists.known,
                l.confirm,
                l.folderError,
                lists.imageMb,
                l.imageBytes,
                d,
                a,
            )
        }.stateIn(scope, SharingStarted.Eagerly, SettingsUiState(account))

    /** Handles the system Back: returns true when it was consumed (closes a dialog or the picker). */
    fun onBack(): Boolean {
        val l = local.value
        val page = l.page
        return when {
            l.confirm != null -> {
                local.value = l.copy(confirm = null)
                true
            }

            page is SettingsPage.ThemeEditor && themeEditor.onBack() -> {
                true
            }

            dailyLocal.value.importReport != null -> {
                dismissImportReport()
                true
            }

            page is SettingsPage.PickJournal -> {
                journalBack()
                true
            }

            l.page != SettingsPage.Home -> {
                home()
                true
            }

            else -> {
                false
            }
        }
    }

    fun setInterval(minutes: Int) = store.setIntervalMinutes(minutes)

    fun setNetwork(network: SyncNetwork) = store.setNetwork(network)

    init {
        scope.launch { local.update { it.copy(imageBytes = imageCache.size()) } }
    }

    /** A choice of Settings › Images: stored, and applied to the cache the next time the app starts. */
    fun setImageCacheMb(mb: Int) = store.setImageCacheMb(mb)

    fun askClearImages() = local.update { it.copy(confirm = SettingsConfirm.ClearImages) }

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

            SettingsConfirm.ClearImages -> {
                local.update { it.copy(confirm = null) }
                scope.launch {
                    imageCache.clear()
                    local.update { it.copy(imageBytes = imageCache.size()) }
                }
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
        when (val result = store.addExclusion(kind, path)) {
            is AddResult.Added -> {
                local.value = Local()
                syncAfterChange()
            }

            AddResult.Blank -> {
                local.update { it.copy(confirm = null, folderError = FolderError.Blank) }
            }

            AddResult.Duplicate -> {
                local.update { it.copy(confirm = null, folderError = FolderError.AlreadyListed) }
            }

            is AddResult.JournalFolder -> {
                local.update { it.copy(confirm = null, folderError = FolderError.JournalFolder(result.journal)) }
            }
        }
    }

    private fun syncAfterChange() {
        scope.launch { sync(SyncTrigger.Manual) }
    }

    // ---- Settings › Appearance ----

    /** Custom with an empty slot seeds it from the current preset and opens the editor. */
    fun setTheme(theme: ThemeChoice) {
        val current = appearance.current()
        if (theme == ThemeChoice.CUSTOM && current.custom == null) {
            openThemeEditor()
        } else {
            appearance.setTheme(theme)
        }
    }

    fun setMode(mode: ModeChoice) = appearance.setMode(mode)

    fun setAccent(accent: AccentSource) = appearance.setAccent(accent)

    /** Selects Custom (seeding an empty slot from the current preset) and opens the editor. */
    fun openThemeEditor() {
        appearance.selectCustomOrSeed(appearance.current().baseTheme())
        themeEditor.reset()
        local.update { it.copy(page = SettingsPage.ThemeEditor, confirm = null) }
    }

    // ---- Settings › Daily notes ----

    /** Back to the home page, keeping what it shows (the Image cache size). */
    private fun home() = local.update { Local(imageBytes = it.imageBytes) }

    private fun editJournal(change: (SettingsPage.PickJournal) -> SettingsPage.PickJournal) =
        local.update { l -> (l.page as? SettingsPage.PickJournal)?.let { l.copy(page = change(it)) } ?: l }

    /** The Folder picker, open on the current Journal folder. */
    fun openJournalPicker() =
        local.update { it.copy(page = SettingsPage.PickJournal(FolderPickerState(state.value.daily.config.folder)), confirm = null) }

    fun journalOpen(path: String) = editJournal { SettingsPage.PickJournal(it.picker.open(path)) }

    /** The picker's ←: up one level, closing it at the Vault root. */
    fun journalBack() {
        val page = local.value.page as? SettingsPage.PickJournal ?: return
        page.picker.back()?.let { up -> editJournal { SettingsPage.PickJournal(up) } } ?: home()
    }

    fun journalNewFolder() = editJournal { SettingsPage.PickJournal(it.picker.startNewFolder()) }

    fun journalNewFolderText(text: String) = editJournal { SettingsPage.PickJournal(it.picker.onNewFolder(text)) }

    fun journalNewFolderDone() {
        val page = local.value.page as? SettingsPage.PickJournal ?: return
        scope.launch {
            val normalised = daily.normaliseFolder(page.picker.typedPath())
            editJournal { if (it == page) SettingsPage.PickJournal(it.picker.withNewFolder(normalised)) else it }
        }
    }

    /** "Choose": the picker's Folder becomes the Journal folder, unless it breaks a Folder rule. */
    fun journalConfirm() {
        val page = local.value.page as? SettingsPage.PickJournal ?: return
        scope.launch {
            when (val result = daily.setFolder(page.picker.path)) {
                is FolderResult.Saved -> home()
                is FolderResult.Refused -> editJournal { if (it.picker == page.picker) it.copy(error = result.error) else it }
            }
        }
    }

    fun openTemplatePicker() {
        local.update { it.copy(page = SettingsPage.PickTemplate(), confirm = null) }
        searchTemplates("")
    }

    fun onTemplateQuery(query: String) {
        local.update { l -> (l.page as? SettingsPage.PickTemplate)?.let { l.copy(page = it.copy(query = query)) } ?: l }
        searchTemplates(query)
    }

    private fun searchTemplates(query: String) {
        scope.launch {
            val found = daily.suggest(query)
            local.update { l ->
                val page = l.page as? SettingsPage.PickTemplate
                if (page?.query == query) l.copy(page = page.copy(suggestions = found)) else l
            }
        }
    }

    /** A Note of the picker ([path] with `.md`), or `""` for None. */
    fun chooseTemplate(path: String) {
        scope.launch {
            daily.setTemplate(path)
            home()
        }
    }

    /** The Date format field: saved at once when valid, else the error shows and the saved format stays. */
    fun onFormat(text: String) {
        dailyLocal.update { it.copy(formatDraft = text, formatError = null) }
        scope.launch {
            formatLock.withLock {
                val result = daily.setFormat(text)
                dailyLocal.update {
                    if (it.formatDraft != text) it else it.copy(formatError = result.takeUnless { r -> r is FormatResult.Saved })
                }
            }
        }
    }

    /** A Date locale code, or null for Automatic. */
    fun setDateLocale(code: String?) {
        scope.launch { daily.setLocale(code) }
    }

    /** One of `WEEK_START_CHOICES`, or null for Automatic (the phone locale). */
    fun setWeekStart(day: DayOfWeek?) {
        scope.launch { daily.setWeekStart(day) }
    }

    /** Import from Obsidian on demand; its report shows as a dialog. A second tap while it runs does nothing. */
    fun importFromObsidian() {
        if (dailyLocal.value.importing) return
        dailyLocal.update { it.copy(importing = true) }
        scope.launch {
            try {
                val report = daily.importFromObsidian()
                // The imported format replaces whatever was being typed.
                dailyLocal.update { it.copy(importReport = report, formatDraft = null, formatError = null) }
            } finally {
                dailyLocal.update { it.copy(importing = false) }
            }
        }
    }

    fun dismissImportReport() = dailyLocal.update { it.copy(importReport = null) }

    private fun dailyUiState(
        config: DailyConfig,
        conflict: String?,
        templateLocal: Boolean,
        l: DailyLocal,
    ): DailyUiState {
        val at = now()
        val code = config.dateLocale(phoneLanguage)
        val format = l.formatDraft ?: config.format
        val example = format.ifBlank { config.format }

        fun month(code: String) = MomentFormat.format(at.date, at.time, "MMMM", DateLocales.forCode(code))
        return DailyUiState(
            config = config,
            conflict = conflict,
            templateLocal = templateLocal,
            format = format,
            formatError = l.formatError,
            formatExample = MomentFormat.format(at.date, at.time, example, DateLocales.forCode(code)),
            localeInUse = code,
            monthExamples =
                buildMap {
                    put(null, month(config.localeDetected?.takeIf { config.locale == null } ?: phoneDateLocale(phoneLanguage)))
                    DateLocales.all.forEach { put(it.code, month(it.code)) }
                },
            importing = l.importing,
            importReport = l.importReport,
        )
    }
}
