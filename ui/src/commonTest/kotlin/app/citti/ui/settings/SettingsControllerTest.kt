package app.citti.ui.settings

import app.citti.core.appearance.AccentSource
import app.citti.core.appearance.Appearance
import app.citti.core.appearance.BaseTheme
import app.citti.core.appearance.CustomTheme
import app.citti.core.appearance.ModeChoice
import app.citti.core.appearance.ThemeChoice
import app.citti.core.daily.IMPORT_FOLDER
import app.citti.core.daily.ImportReport
import app.citti.core.note.InvalidNameException
import app.citti.core.note.NameError
import app.citti.core.note.Suggestion
import app.citti.core.settings.AddResult
import app.citti.core.settings.DailyConfig
import app.citti.core.settings.ExclusionKind
import app.citti.core.settings.FolderResult
import app.citti.core.settings.FormatResult
import app.citti.core.settings.SyncNetwork
import app.citti.core.sync.SyncTrigger
import app.citti.ui.components.FolderPickerState
import app.citti.ui.image.ImageCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeStore(
    val local: Map<String, Pair<Int, Int>> = emptyMap(),
) : SettingsStore {
    val interval = MutableStateFlow(30)
    val network = MutableStateFlow(SyncNetwork.ANY)
    val unsynced = MutableStateFlow(emptyList<String>())
    val hidden = MutableStateFlow(emptyList<String>())
    val imageMb = MutableStateFlow(500)

    override fun setImageCacheMb(mb: Int) {
        imageMb.value = mb
    }

    override fun setIntervalMinutes(minutes: Int) {
        interval.value = minutes
    }

    override fun setNetwork(network: SyncNetwork) {
        this.network.value = network
    }

    override fun addExclusion(
        kind: ExclusionKind,
        path: String,
    ): AddResult {
        val flow = if (kind == ExclusionKind.UNSYNCED) unsynced else hidden
        if (path in flow.value) return AddResult.Duplicate
        if (kind == ExclusionKind.UNSYNCED && path == "journal") return AddResult.JournalFolder("journal")
        flow.value = flow.value + path
        return AddResult.Added(path)
    }

    override fun removeExclusion(
        kind: ExclusionKind,
        path: String,
    ) {
        val flow = if (kind == ExclusionKind.UNSYNCED) unsynced else hidden
        flow.value = flow.value - path
    }

    override fun notesUnder(path: String) = local[path]?.first ?: 0

    override fun pendingUnder(path: String) = local[path]?.second ?: 0
}

/** Daily notes settings with the core rules that matter here: Unsynced `archive`, formats with `/` or no `DD`. */
private class FakeDailyStore : DailySettingsStore {
    val config = MutableStateFlow(DailyConfig())
    val conflict = MutableStateFlow<String?>(null)
    val local = mutableSetOf("templates/daily.md")
    val notes = listOf(Suggestion(1, "daily", "templates"), Suggestion(2, "Weekly", "templates"), Suggestion(3, "Inbox", ""))
    var report: ImportReport? = ImportReport(read = mapOf(IMPORT_FOLDER to "journal"))
    var imports = 0

    override fun config(): Flow<DailyConfig> = config

    override fun conflict(): Flow<String?> = conflict

    override suspend fun normaliseFolder(path: String): Result<String> {
        val p = path.trim().trim('/')
        return if (p == "archive" ||
            p.startsWith("archive/")
        ) {
            Result.failure(InvalidNameException(NameError.Unsynced))
        } else {
            Result.success(p)
        }
    }

    override suspend fun setFolder(path: String): FolderResult =
        normaliseFolder(path).fold(
            { p ->
                config.update { it.copy(folder = p, localeDetected = if (p == it.folder) it.localeDetected else null) }
                FolderResult.Saved(p)
            },
            { FolderResult.Refused((it as InvalidNameException).error) },
        )

    override suspend fun setTemplate(path: String) = config.update { it.copy(template = path) }

    override suspend fun isLocalNote(path: String) = path in local

    override suspend fun suggest(query: String) = notes.filter { it.title.startsWith(query, ignoreCase = true) }

    override suspend fun setFormat(format: String): FormatResult {
        if ('/' in format || '\\' in format) return FormatResult.Slash
        if (':' in format) return FormatResult.InvalidTitle(NameError.UnsafeCharacter)
        if ("D" !in format || "M" !in format || "Y" !in format) return FormatResult.RoundTrip
        config.update { it.copy(format = format) }
        return FormatResult.Saved(format)
    }

    override suspend fun setLocale(code: String?) = config.update { it.copy(locale = code, localeDetected = code) }

    override suspend fun setWeekStart(day: DayOfWeek?) = config.update { it.copy(weekStart = day) }

    override suspend fun importFromObsidian(): ImportReport? {
        imports++
        return report
    }
}

class FakeAppearanceStore(
    initial: Appearance = Appearance(),
) : AppearanceStore {
    val state = MutableStateFlow(initial)

    override fun appearance(): Flow<Appearance> = state

    override fun current() = state.value

    override fun setTheme(theme: ThemeChoice) = state.update { it.copy(theme = theme) }

    override fun setMode(mode: ModeChoice) = state.update { it.copy(mode = mode) }

    override fun setAccent(accent: AccentSource) = state.update { it.copy(accent = accent) }

    override fun setCustom(custom: CustomTheme?) = state.update { it.copy(custom = custom) }

    override fun updateCustom(
        seedBase: BaseTheme,
        change: (CustomTheme) -> CustomTheme,
    ) = state.update { it.copy(custom = change(it.custom ?: CustomTheme(seedBase))) }

    override fun selectCustomOrSeed(base: BaseTheme): CustomTheme {
        val custom = state.value.custom ?: CustomTheme(base)
        state.update { it.copy(theme = ThemeChoice.CUSTOM, custom = custom) }
        return custom
    }
}

private class FakeImageCache(
    var bytes: Long = 312L * 1024 * 1024,
) : ImageCache {
    var cleared = 0

    override suspend fun size() = bytes

    override suspend fun clear() {
        cleared++
        bytes = 0
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsControllerTest {
    private val images = FakeImageCache()

    private val triggers = mutableListOf<SyncTrigger>()
    private var signedOut = 0
    private var signInAgain = 0

    private val daily = FakeDailyStore()
    private val appearance = FakeAppearanceStore()

    private fun TestScope.controller(store: FakeStore) =
        SettingsController(
            scope = backgroundScope,
            account = AccountInfo("cloud.example.com", "alice"),
            store = store,
            interval = store.interval,
            network = store.network,
            status = MutableStateFlow(null),
            unsynced = store.unsynced,
            hidden = store.hidden,
            knownFolders = MutableStateFlow(listOf("a", "a/b")),
            imageCacheMb = store.imageMb,
            imageCache = images,
            sync = { triggers += it },
            signOut = { signedOut++ },
            signInAgain = { signInAgain++ },
            daily = daily,
            appearance = appearance,
            phoneLanguage = "de",
            now = { LocalDateTime(2026, 10, 31, 9, 30) },
        )

    @Test
    fun addingHiddenFolderStoresItAndSyncsManually() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore()
            val c = controller(store)
            c.openPicker(ExclusionKind.HIDDEN)
            assertEquals(SettingsPage.PickFolder(ExclusionKind.HIDDEN), c.state.value.page)
            c.addFolder(ExclusionKind.HIDDEN, " /secret/ ")
            assertEquals(listOf("secret"), c.state.value.hidden)
            assertEquals(SettingsPage.Home, c.state.value.page)
            assertEquals(listOf(SyncTrigger.Manual), triggers)
        }

    @Test
    fun unsyncedFolderWithLocalNotesAsksFirst() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore(local = mapOf("old" to (12 to 2)))
            val c = controller(store)
            c.addFolder(ExclusionKind.UNSYNCED, "old")
            assertEquals(SettingsConfirm.AddUnsynced("old", 12, 2), c.state.value.confirm)
            assertTrue(
                c.state.value.unsynced
                    .isEmpty(),
            )
            assertTrue(triggers.isEmpty())

            c.dismissConfirm()
            assertNull(c.state.value.confirm)
            assertTrue(triggers.isEmpty())

            c.addFolder(ExclusionKind.UNSYNCED, "old")
            c.confirm()
            assertEquals(listOf("old"), c.state.value.unsynced)
            assertNull(c.state.value.confirm)
            assertEquals(listOf(SyncTrigger.Manual), triggers)
        }

    @Test
    fun unsyncedFolderWithoutLocalNotesAndHiddenNeverAsk() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore(local = mapOf("old" to (5 to 0)))
            val c = controller(store)
            c.addFolder(ExclusionKind.UNSYNCED, "empty")
            assertNull(c.state.value.confirm)
            c.addFolder(ExclusionKind.HIDDEN, "old")
            assertNull(c.state.value.confirm)
            assertEquals(listOf("empty"), c.state.value.unsynced)
            assertEquals(listOf("old"), c.state.value.hidden)
        }

    @Test
    fun blankAndDuplicateAreRefused() =
        runTest(UnconfinedTestDispatcher()) {
            val c = controller(FakeStore())
            c.openPicker(ExclusionKind.UNSYNCED)
            c.addFolder(ExclusionKind.UNSYNCED, " / ")
            assertEquals(FolderError.Blank, c.state.value.folderError)
            c.addFolder(ExclusionKind.UNSYNCED, "x")
            c.openPicker(ExclusionKind.UNSYNCED)
            c.addFolder(ExclusionKind.UNSYNCED, "x")
            assertEquals(FolderError.AlreadyListed, c.state.value.folderError)
            assertEquals(listOf("x"), c.state.value.unsynced)
            assertEquals(1, triggers.size)
        }

    @Test
    fun theJournalFolderIsRefusedAsUnsynced() =
        runTest(UnconfinedTestDispatcher()) {
            val c = controller(FakeStore())
            c.openPicker(ExclusionKind.UNSYNCED)
            c.addFolder(ExclusionKind.UNSYNCED, "journal")
            assertEquals(FolderError.JournalFolder("journal"), c.state.value.folderError)
            assertEquals(emptyList(), c.state.value.unsynced)
            assertEquals(0, triggers.size)
        }

    @Test
    fun removingSyncsAndBackWalksOutOfPickerAndDialogs() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore()
            val c = controller(store)
            c.addFolder(ExclusionKind.UNSYNCED, "x")
            c.removeFolder(ExclusionKind.UNSYNCED, "x")
            assertTrue(
                c.state.value.unsynced
                    .isEmpty(),
            )
            assertEquals(2, triggers.size)

            c.openPicker(ExclusionKind.HIDDEN)
            c.askSignOut()
            assertTrue(c.onBack())
            assertNull(c.state.value.confirm)
            assertTrue(c.onBack())
            assertEquals(SettingsPage.Home, c.state.value.page)
            assertFalse(c.onBack())
        }

    @Test
    fun syncSettingsPassThroughAndAccountActions() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore()
            val c = controller(store)
            c.setInterval(0)
            c.setNetwork(SyncNetwork.UNMETERED)
            assertEquals(0, c.state.value.intervalMinutes)
            assertEquals(SyncNetwork.UNMETERED, c.state.value.network)
            c.syncNow()
            assertEquals(listOf(SyncTrigger.Manual), triggers)

            c.signInAgain()
            assertEquals(1, signInAgain)
            assertEquals(0, signedOut)
            c.askSignOut()
            assertEquals(SettingsConfirm.SignOut, c.state.value.confirm)
            assertEquals(0, signedOut)
            c.confirm()
            assertEquals(1, signedOut)
            assertNull(c.state.value.confirm)
        }

    @Test
    fun imageCacheSizeChoiceIsStoredAndShown() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore()
            val c = controller(store)
            assertEquals(500, c.state.value.imageCacheMb)
            assertEquals(312L * 1024 * 1024, c.state.value.imageCacheBytes)
            c.setImageCacheMb(2000)
            assertEquals(2000, store.imageMb.value)
            assertEquals(2000, c.state.value.imageCacheMb)
        }

    @Test
    fun clearingTheImageCacheAsksFirstThenEmptiesIt() =
        runTest(UnconfinedTestDispatcher()) {
            val c = controller(FakeStore())
            c.askClearImages()
            assertEquals(SettingsConfirm.ClearImages, c.state.value.confirm)
            c.dismissConfirm()
            assertEquals(0, images.cleared)
            c.askClearImages()
            c.confirm()
            assertEquals(1, images.cleared)
            assertNull(c.state.value.confirm)
            assertEquals(0L, c.state.value.imageCacheBytes)
        }

    // ---- Settings › Daily notes ----

    @Test
    fun theJournalFolderPickerOpensOnItAndChooseSavesIt() =
        runTest(UnconfinedTestDispatcher()) {
            val c = controller(FakeStore())
            c.openJournalPicker()
            assertEquals(SettingsPage.PickJournal(FolderPickerState("journal")), c.state.value.page)
            c.journalBack()
            c.journalOpen("notes")
            c.journalNewFolder()
            c.journalNewFolderText("daily")
            c.journalNewFolderDone()
            assertEquals(SettingsPage.PickJournal(FolderPickerState("notes/daily")), c.state.value.page)
            c.journalConfirm()
            assertEquals("notes/daily", daily.config.value.folder)
            assertEquals(SettingsPage.Home, c.state.value.page)
        }

    @Test
    fun anUnsyncedJournalFolderIsRefusedInThePicker() =
        runTest(UnconfinedTestDispatcher()) {
            val c = controller(FakeStore())
            c.openJournalPicker()
            c.journalOpen("archive")
            c.journalConfirm()
            val page = c.state.value.page as SettingsPage.PickJournal
            assertEquals(NameError.Unsynced, page.error)
            assertEquals("journal", daily.config.value.folder)
            // Back walks up the picker, then closes it at the Vault root.
            assertTrue(c.onBack())
            assertEquals(SettingsPage.PickJournal(FolderPickerState("")), c.state.value.page)
            assertTrue(c.onBack())
            assertEquals(SettingsPage.Home, c.state.value.page)
        }

    @Test
    fun theTemplatePickerSearchesTheNotesAndOffersNone() =
        runTest(UnconfinedTestDispatcher()) {
            val c = controller(FakeStore())
            assertTrue(c.state.value.daily.templateLocal)
            c.openTemplatePicker()
            assertEquals(3, (c.state.value.page as SettingsPage.PickTemplate).suggestions.size)
            c.onTemplateQuery("we")
            val page = c.state.value.page as SettingsPage.PickTemplate
            assertEquals(listOf("Weekly"), page.suggestions.map { it.title })
            c.chooseTemplate(page.suggestions.single().templatePath())
            assertEquals("templates/Weekly.md", daily.config.value.template)
            assertEquals(SettingsPage.Home, c.state.value.page)
            c.openTemplatePicker()
            c.chooseTemplate("")
            assertEquals("", daily.config.value.template)
            assertTrue(c.state.value.daily.templateLocal)
            assertEquals("Inbox.md", Suggestion(3, "Inbox", "").templatePath())
        }

    @Test
    fun aTemplateThatIsNotLocalIsMarkedNotSynced() =
        runTest(UnconfinedTestDispatcher()) {
            daily.config.value = DailyConfig(template = "private/daily.md")
            val c = controller(FakeStore())
            assertFalse(c.state.value.daily.templateLocal)
        }

    @Test
    fun theDateFormatIsSavedWhenValidWithALiveExampleAndErrors() =
        runTest(UnconfinedTestDispatcher()) {
            val c = controller(FakeStore())
            assertEquals("YYYY-MM-DD", c.state.value.daily.format)
            assertEquals("2026-10-31", c.state.value.daily.formatExample)
            c.onFormat("DD.MM.YYYY")
            assertEquals("DD.MM.YYYY", daily.config.value.format)
            assertEquals("31.10.2026", c.state.value.daily.formatExample)
            assertNull(c.state.value.daily.formatError)
            c.onFormat("YYYY/MM/DD")
            assertEquals(FormatResult.Slash, c.state.value.daily.formatError)
            assertEquals("YYYY/MM/DD", c.state.value.daily.format)
            assertEquals("DD.MM.YYYY", daily.config.value.format)
            c.onFormat("YYYY-MM-DD HH:mm")
            assertEquals(FormatResult.InvalidTitle(NameError.UnsafeCharacter), c.state.value.daily.formatError)
            assertEquals("DD.MM.YYYY", daily.config.value.format)
            c.onFormat("YYYY-MM")
            assertEquals(FormatResult.RoundTrip, c.state.value.daily.formatError)
            assertEquals("2026-10", c.state.value.daily.formatExample)
        }

    @Test
    fun theDateLocaleFallsBackToThePhoneThenFollowsDetectionAndTheManualChoice() =
        runTest(UnconfinedTestDispatcher()) {
            val c = controller(FakeStore())
            // Automatic, nothing detected: the phone's language (de).
            assertEquals("de", c.state.value.daily.localeInUse)
            assertEquals("Oktober", c.state.value.daily.monthExamples[null])
            assertEquals("octobre", c.state.value.daily.monthExamples["fr"])
            assertEquals(8, c.state.value.daily.monthExamples.size)
            daily.config.update { it.copy(localeDetected = "fr") }
            assertEquals("fr", c.state.value.daily.localeInUse)
            assertEquals("octobre", c.state.value.daily.monthExamples[null])
            c.setDateLocale("es")
            assertEquals("es", daily.config.value.locale)
            assertEquals("es", c.state.value.daily.localeInUse)
            // Automatic is now the phone's language again: the manual choice also set the detected one.
            assertEquals("Oktober", c.state.value.daily.monthExamples[null])
            c.setDateLocale(null)
            assertNull(daily.config.value.locale)
            assertEquals("en", phoneDateLocale("ja"))
            assertEquals("pt", phoneDateLocale("PT"))
        }

    @Test
    fun weekStartPassesThrough() =
        runTest(UnconfinedTestDispatcher()) {
            val c = controller(FakeStore())
            c.setWeekStart(DayOfWeek.SUNDAY)
            assertEquals(DayOfWeek.SUNDAY, c.state.value.daily.config.weekStart)
            c.setWeekStart(null)
            assertNull(c.state.value.daily.config.weekStart)
        }

    @Test
    fun importFromObsidianShowsItsReportUntilDismissed() =
        runTest(UnconfinedTestDispatcher()) {
            val c = controller(FakeStore())
            c.onFormat("YYYY-MM")
            c.importFromObsidian()
            assertEquals(1, daily.imports)
            assertEquals(daily.report, c.state.value.daily.importReport)
            assertFalse(c.state.value.daily.importing)
            assertNull(c.state.value.daily.formatError)
            assertEquals("YYYY-MM-DD", c.state.value.daily.format)
            assertTrue(c.onBack())
            assertNull(c.state.value.daily.importReport)

            daily.report = null
            c.importFromObsidian()
            assertNull(c.state.value.daily.importReport)
        }

    @Test
    fun theGuardConflictIsShown() =
        runTest(UnconfinedTestDispatcher()) {
            val c = controller(FakeStore())
            assertNull(c.state.value.daily.conflict)
            daily.conflict.value = "journal"
            assertEquals("journal", c.state.value.daily.conflict)
        }

    @Test
    fun appearanceChoicesAreWrittenAndShown() =
        runTest(UnconfinedTestDispatcher()) {
            val c = controller(FakeStore())
            c.setTheme(ThemeChoice.PAPER)
            c.setMode(ModeChoice.DARK)
            c.setAccent(AccentSource.DYNAMIC)
            assertEquals(Appearance(ThemeChoice.PAPER, ModeChoice.DARK, AccentSource.DYNAMIC), c.state.value.appearance)
            assertEquals(SettingsPage.Home, c.state.value.page)
        }

    @Test
    fun choosingCustomWithAnEmptySlotSeedsItFromThePresetAndOpensTheEditor() =
        runTest(UnconfinedTestDispatcher()) {
            val c = controller(FakeStore())
            c.setTheme(ThemeChoice.INDEX)
            c.setTheme(ThemeChoice.CUSTOM)
            assertEquals(ThemeChoice.CUSTOM, c.state.value.appearance.theme)
            assertEquals(CustomTheme(BaseTheme.INDEX), c.state.value.appearance.custom)
            assertEquals(SettingsPage.ThemeEditor, c.state.value.page)
            assertTrue(c.onBack())
            assertEquals(SettingsPage.Home, c.state.value.page)
        }

    @Test
    fun choosingCustomWithAFilledSlotJustSelectsIt() =
        runTest(UnconfinedTestDispatcher()) {
            appearance.state.value = Appearance(ThemeChoice.PAPER, custom = CustomTheme(BaseTheme.SIGNAL))
            val c = controller(FakeStore())
            c.setTheme(ThemeChoice.CUSTOM)
            assertEquals(ThemeChoice.CUSTOM, c.state.value.appearance.theme)
            assertEquals(SettingsPage.Home, c.state.value.page)
        }

    @Test
    fun editCustomThemeSeedsAnEmptySlotAndOpensTheEditor() =
        runTest(UnconfinedTestDispatcher()) {
            appearance.state.value = Appearance(ThemeChoice.PAPER)
            val c = controller(FakeStore())
            c.openThemeEditor()
            assertEquals(Appearance(ThemeChoice.CUSTOM, custom = CustomTheme(BaseTheme.PAPER)), c.state.value.appearance)
            assertEquals(SettingsPage.ThemeEditor, c.state.value.page)
        }
}
