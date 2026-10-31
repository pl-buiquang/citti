package app.citti.ui.settings

import app.citti.core.daily.DailyGuard
import app.citti.core.daily.DateLocales
import app.citti.core.daily.ImportReport
import app.citti.core.daily.ObsidianDailyImport
import app.citti.core.note.LinkResolver
import app.citti.core.note.LinkTarget
import app.citti.core.note.NameRules
import app.citti.core.note.Suggestion
import app.citti.core.settings.DailyConfig
import app.citti.core.settings.DailySettings
import app.citti.core.settings.ExclusionRepository
import app.citti.core.settings.FolderResult
import app.citti.core.settings.FormatResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.datetime.DayOfWeek

/** What Settings › Daily notes reads and changes; [CoreDailySettingsStore] adapts core, tests use a fake. */
interface DailySettingsStore {
    fun config(): Flow<DailyConfig>

    /** The Journal folder while it lies under an Unsynced folder ([DailyGuard.conflict]), else null. */
    fun conflict(): Flow<String?>

    /** `NameRules.normaliseFolder` against the current Unsynced folders: the picker's "New folder…". */
    suspend fun normaliseFolder(path: String): Result<String>

    suspend fun setFolder(path: String): FolderResult

    /** A Vault path, or blank for no Template. */
    suspend fun setTemplate(path: String)

    /** True when [path] (a Vault path with `.md`) is a Note on this phone. */
    suspend fun isLocalNote(path: String): Boolean

    /** The Template picker's search: `LinkResolver.suggest`, synced Notes only. */
    suspend fun suggest(query: String): List<Suggestion>

    suspend fun setFormat(format: String): FormatResult

    suspend fun setLocale(code: String?)

    suspend fun setWeekStart(day: DayOfWeek?)

    /** Import from Obsidian on demand; null while signed out. */
    suspend fun importFromObsidian(): ImportReport?
}

/** [DailySettingsStore] over core, every database read and write on [io]. */
class CoreDailySettingsStore(
    private val settings: DailySettings,
    private val guard: DailyGuard,
    private val exclusions: ExclusionRepository,
    private val links: LinkResolver,
    private val import: () -> ObsidianDailyImport?,
    private val io: CoroutineDispatcher,
) : DailySettingsStore {
    override fun config(): Flow<DailyConfig> = settings.configFlow()

    override fun conflict(): Flow<String?> = guard.conflict()

    override suspend fun normaliseFolder(path: String): Result<String> = withContext(io) { NameRules.normaliseFolder(path, exclusions) }

    override suspend fun setFolder(path: String): FolderResult = withContext(io) { settings.setFolder(path) }

    override suspend fun setTemplate(path: String) = withContext(io) { settings.setTemplate(path) }

    override suspend fun isLocalNote(path: String): Boolean = withContext(io) { links.resolveMarkdownLink("", path) is LinkTarget.Note }

    override suspend fun suggest(query: String): List<Suggestion> = withContext(io) { links.suggest(query) }

    override suspend fun setFormat(format: String): FormatResult = withContext(io) { settings.setFormat(format) }

    override suspend fun setLocale(code: String?) = withContext(io) { settings.setLocale(code) }

    override suspend fun setWeekStart(day: DayOfWeek?) = withContext(io) { settings.setWeekStart(day) }

    override suspend fun importFromObsidian(): ImportReport? = import()?.let { withContext(io) { it.run() } }
}

/**
 * The Date locale Templates render with when the setting is Automatic and nothing was detected yet: the phone's
 * language when it is one of the Date locales, else `en`.
 */
fun phoneDateLocale(phoneLanguage: String): String =
    DateLocales.all.firstOrNull { it.code == phoneLanguage.lowercase() }?.code ?: DateLocales.en.code

/** The Date locale code in use: the manual one, else the detected one, else [phoneDateLocale]. */
fun DailyConfig.dateLocale(phoneLanguage: String): String = locale ?: localeDetected ?: phoneDateLocale(phoneLanguage)

/** The Vault path of a suggested Note, as the Template setting stores it. */
fun Suggestion.templatePath(): String = if (folder.isEmpty()) "$title.md" else "$folder/$title.md"
