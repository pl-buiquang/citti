package app.citti.core.settings

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.citti.core.daily.DateLocales
import app.citti.core.daily.MomentFormat
import app.citti.core.db.CittiDatabase
import app.citti.core.db.SettingQueries
import app.citti.core.note.InvalidNameException
import app.citti.core.note.NameError
import app.citti.core.note.NameRules
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Prefix of every Daily notes `setting` key; they are device-local and wiped at sign-out. */
const val DAILY_PREFIX = "daily."

/** `setting` key of the Journal folder, a Vault path. */
const val KEY_DAILY_FOLDER = "daily.folder"

/** `setting` key of the Template, a Vault path with `.md`, or `""` for no Template. */
const val KEY_DAILY_TEMPLATE = "daily.template"

/** `setting` key of the Date format, a Moment format. */
const val KEY_DAILY_FORMAT = "daily.format"

/** `setting` key of the manual Date locale (`en`…`nl`). */
const val KEY_DAILY_LOCALE = "daily.locale"

/** `setting` key of the detected Date locale; set means detection is done. */
const val KEY_DAILY_LOCALE_DETECTED = "daily.locale-detected"

/** `setting` key of the first day of the Calendar week; unset follows the phone locale. */
const val KEY_DAILY_WEEK_START = "daily.week-start"

/** `setting` key of the cached Template text, JSON `{"path":…, "etag":…, "text":…}`. */
const val KEY_DAILY_TEMPLATE_CACHE = "daily.template-cache"

/** `setting` key set to `1` once Import from Obsidian ran automatically. */
const val KEY_DAILY_IMPORT_DONE = "daily.import-done"

const val DEFAULT_DAILY_FOLDER = "journal"
const val DEFAULT_DAILY_TEMPLATE = "templates/daily.md"
const val DEFAULT_DAILY_FORMAT = "YYYY-MM-DD"

/** The days the Calendar week can start on. */
val WEEK_START_CHOICES = listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY, DayOfWeek.SATURDAY)

/** The dates a Date format must give back when parsed (`parse(format(d)) == d`). */
internal val FORMAT_CHECK_DATES = listOf(LocalDate(2026, 1, 5), LocalDate(2026, 10, 31), LocalDate(2026, 12, 31))

/** A snapshot of the Daily notes settings: what the rest of the app reads. */
data class DailyConfig(
    val folder: String = DEFAULT_DAILY_FOLDER,
    val template: String = DEFAULT_DAILY_TEMPLATE,
    val format: String = DEFAULT_DAILY_FORMAT,
    /** The manual Date locale, or null for automatic. */
    val locale: String? = null,
    /** The detected Date locale, or null while detection hasn't decided. */
    val localeDetected: String? = null,
    /** The first day of the Calendar week, or null to follow the phone locale. */
    val weekStart: DayOfWeek? = null,
)

/** The Template text cached for [path], with the ETag it was fetched with. */
@Serializable
data class TemplateCache(
    val path: String,
    val etag: String? = null,
    val text: String,
)

/** What [DailySettings.setFolder] did. */
sealed interface FolderResult {
    data class Saved(
        val path: String,
    ) : FolderResult

    /** The path breaks a Folder rule ([NameError.Unsynced] when it lies under an Unsynced folder). */
    data class Refused(
        val error: NameError,
    ) : FolderResult
}

/** What [DailySettings.setFormat] did. */
sealed interface FormatResult {
    data class Saved(
        val format: String,
    ) : FormatResult

    /** The format contains `/` or `\`: Daily note titles are flat names. */
    data object Slash : FormatResult

    /** A date written in the format can't be read back (no day, month or year, for example). */
    data object RoundTrip : FormatResult

    /** A date written in the format isn't a valid Note title ([NameError.UnsafeCharacter] for `:`, for example). */
    data class InvalidTitle(
        val error: NameError,
    ) : FormatResult
}

/**
 * Reads and writes the Daily notes settings (`daily.*` keys of the `setting` table). The Journal folder can never be
 * an Unsynced folder: [setFolder] refuses one, and [ExclusionRepository.add] refuses to make it one.
 */
class DailySettings(
    db: CittiDatabase,
    private val exclusions: ExclusionRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val queries = db.settingQueries
    private val json = Json { ignoreUnknownKeys = true }

    fun config(): DailyConfig =
        parseConfig(queries.selectSettingsWithPrefix(DAILY_PREFIX).executeAsList().associate { it.key to it.value_ })

    fun configFlow(): Flow<DailyConfig> =
        queries
            .selectSettingsWithPrefix(DAILY_PREFIX)
            .asFlow()
            .mapToList(dispatcher)
            .map { rows -> parseConfig(rows.associate { it.key to it.value_ }) }
            .distinctUntilChanged()

    fun folder(): String = journalFolder(queries)

    fun folderFlow(): Flow<String> =
        queries
            .selectSetting(KEY_DAILY_FOLDER)
            .asFlow()
            .mapToOneOrNull(dispatcher)
            .map { it ?: DEFAULT_DAILY_FOLDER }
            .distinctUntilChanged()

    /**
     * Sets the Journal folder, normalised by [NameRules.normaliseFolder] (blank is the Vault root). A path under an
     * Unsynced folder is refused. A new folder clears the detected Date locale, so detection runs again.
     */
    fun setFolder(path: String): FolderResult {
        val folder =
            NameRules.normaliseFolder(path, exclusions).getOrElse {
                return FolderResult.Refused((it as InvalidNameException).error)
            }
        if (folder != folder()) {
            queries.transaction {
                queries.upsertSetting(KEY_DAILY_FOLDER, folder)
                queries.deleteSetting(KEY_DAILY_LOCALE_DETECTED)
            }
        }
        return FolderResult.Saved(folder)
    }

    fun template(): String = queries.selectSetting(KEY_DAILY_TEMPLATE).executeAsOneOrNull() ?: DEFAULT_DAILY_TEMPLATE

    /**
     * Sets the Template: a Vault path (`/` trimmed, `.md` added when missing), or blank for no Template. A new path
     * clears the cached Template text.
     */
    fun setTemplate(path: String) {
        val template = normalizeTemplatePath(path)
        if (template == template()) return
        queries.transaction {
            queries.upsertSetting(KEY_DAILY_TEMPLATE, template)
            queries.deleteSetting(KEY_DAILY_TEMPLATE_CACHE)
        }
    }

    fun format(): String = queries.selectSetting(KEY_DAILY_FORMAT).executeAsOneOrNull() ?: DEFAULT_DAILY_FORMAT

    /**
     * Sets the Date format; blank resets it to [DEFAULT_DAILY_FORMAT]. Refuses `/` and `\`, a format that writes a
     * check date as an invalid Note title ([NameRules.validateTitle]), and one that the check dates don't survive a
     * round trip through ([MomentFormat] with the manual or detected Date locale).
     */
    fun setFormat(format: String): FormatResult {
        if (format.isBlank()) {
            queries.deleteSetting(KEY_DAILY_FORMAT)
            return FormatResult.Saved(DEFAULT_DAILY_FORMAT)
        }
        if (format.any { it == '/' || it == '\\' }) return FormatResult.Slash
        val config = config()
        val locale = DateLocales.forCode(config.locale ?: config.localeDetected)
        FORMAT_CHECK_DATES
            .firstNotNullOfOrNull { date ->
                NameRules.validateTitle(MomentFormat.format(date, LocalTime(0, 0), format, locale))
            }?.let { return FormatResult.InvalidTitle(it) }
        val roundTrips =
            FORMAT_CHECK_DATES.all { date ->
                MomentFormat.parse(MomentFormat.format(date, LocalTime(0, 0), format, locale), format, locale) == date
            }
        if (!roundTrips) return FormatResult.RoundTrip
        queries.upsertSetting(KEY_DAILY_FORMAT, format)
        return FormatResult.Saved(format)
    }

    /**
     * The manual Date locale: a code of [DateLocales.all], or null for automatic. A manual choice is also stored as
     * the detected one, so detection stops; automatic clears both, so detection runs again.
     */
    fun setLocale(code: String?) {
        queries.transaction {
            if (code == null) {
                queries.deleteSetting(KEY_DAILY_LOCALE)
                queries.deleteSetting(KEY_DAILY_LOCALE_DETECTED)
            } else {
                require(DateLocales.all.any { it.code == code }) { "Unsupported Date locale: $code" }
                queries.upsertSetting(KEY_DAILY_LOCALE, code)
                queries.upsertSetting(KEY_DAILY_LOCALE_DETECTED, code)
            }
        }
    }

    /** Stores the Date locale detection decided on. */
    fun setLocaleDetected(code: String) {
        require(DateLocales.all.any { it.code == code }) { "Unsupported Date locale: $code" }
        queries.upsertSetting(KEY_DAILY_LOCALE_DETECTED, code)
    }

    /** The first day of the Calendar week, one of [WEEK_START_CHOICES], or null to follow the phone locale. */
    fun setWeekStart(day: DayOfWeek?) {
        if (day == null) {
            queries.deleteSetting(KEY_DAILY_WEEK_START)
            return
        }
        require(day in WEEK_START_CHOICES) { "Unsupported week start: $day" }
        queries.upsertSetting(KEY_DAILY_WEEK_START, day.name)
    }

    /** The cached Template text, or null when none is stored or it can't be read. */
    fun templateCache(): TemplateCache? =
        queries.selectSetting(KEY_DAILY_TEMPLATE_CACHE).executeAsOneOrNull()?.let {
            runCatching { json.decodeFromString<TemplateCache>(it) }.getOrNull()
        }

    fun setTemplateCache(cache: TemplateCache) = queries.upsertSetting(KEY_DAILY_TEMPLATE_CACHE, json.encodeToString(cache))

    fun clearTemplateCache() = queries.deleteSetting(KEY_DAILY_TEMPLATE_CACHE)

    /** True once Import from Obsidian ran automatically. */
    fun importDone(): Boolean = queries.selectSetting(KEY_DAILY_IMPORT_DONE).executeAsOneOrNull() != null

    fun setImportDone() = queries.upsertSetting(KEY_DAILY_IMPORT_DONE, "1")

    private fun parseConfig(values: Map<String, String>): DailyConfig =
        DailyConfig(
            folder = values[KEY_DAILY_FOLDER] ?: DEFAULT_DAILY_FOLDER,
            template = values[KEY_DAILY_TEMPLATE] ?: DEFAULT_DAILY_TEMPLATE,
            format = values[KEY_DAILY_FORMAT] ?: DEFAULT_DAILY_FORMAT,
            locale = values[KEY_DAILY_LOCALE]?.takeIf(::isLocaleCode),
            localeDetected = values[KEY_DAILY_LOCALE_DETECTED]?.takeIf(::isLocaleCode),
            weekStart = values[KEY_DAILY_WEEK_START]?.let { raw -> WEEK_START_CHOICES.firstOrNull { it.name == raw } },
        )

    private fun isLocaleCode(code: String) = DateLocales.all.any { it.code == code }
}

/** The Journal folder stored in the `setting` table. Read by [ExclusionRepository] too, so neither needs the other. */
internal fun journalFolder(queries: SettingQueries): String =
    queries.selectSetting(KEY_DAILY_FOLDER).executeAsOneOrNull() ?: DEFAULT_DAILY_FOLDER

/** A Template path: trimmed, without leading, trailing or doubled `/`, with `.md`; `""` when blank. */
internal fun normalizeTemplatePath(raw: String): String {
    val path = normalizeFolderPath(raw) ?: return ""
    return if (path.endsWith(".md", ignoreCase = true)) path else "$path.md"
}
