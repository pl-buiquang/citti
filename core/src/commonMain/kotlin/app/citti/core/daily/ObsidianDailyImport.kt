package app.citti.core.daily

import app.citti.core.api.ApiError
import app.citti.core.api.ApiResult
import app.citti.core.dav.DavClient
import app.citti.core.dav.DavGet
import app.citti.core.dav.VaultPath
import app.citti.core.note.NameError
import app.citti.core.settings.DailySettings
import app.citti.core.settings.FolderResult
import app.citti.core.settings.FormatResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The Obsidian file of the Daily notes core plugin. */
const val OBSIDIAN_DAILY_NOTES = ".obsidian/daily-notes.json"

/** Keys of [ImportReport.read]: the `daily-notes.json` keys Import from Obsidian reads. */
const val IMPORT_FOLDER = "folder"
const val IMPORT_TEMPLATE = "template"
const val IMPORT_FORMAT = "format"

/** A value of `daily-notes.json` that the Daily notes settings refused; the setting kept its value. */
sealed interface Refusal {
    val key: String
    val value: String

    /** The Journal folder breaks a Folder rule ([NameError.Unsynced] under an Unsynced folder). */
    data class Folder(
        override val value: String,
        val error: NameError,
    ) : Refusal {
        override val key: String get() = IMPORT_FOLDER
    }

    /** The Date format is refused: [result] is [FormatResult.Slash] or [FormatResult.RoundTrip]. */
    data class Format(
        override val value: String,
        val result: FormatResult,
    ) : Refusal {
        override val key: String get() = IMPORT_FORMAT
    }
}

/**
 * What Import from Obsidian did. [read] maps each key it applied ([IMPORT_FOLDER], [IMPORT_TEMPLATE],
 * [IMPORT_FORMAT]) to the value saved; [refused] lists the values kept out. [notFound]: no readable
 * `daily-notes.json`, the settings are untouched. [error]: the file could not be fetched (offline, server error).
 */
data class ImportReport(
    val read: Map<String, String> = emptyMap(),
    val refused: List<Refusal> = emptyList(),
    val notFound: Boolean = false,
    val error: ApiError? = null,
) {
    /** True when the file gave at least one value, saved or refused: the case the snackbar is shown for. */
    val readSomething: Boolean get() = read.isNotEmpty() || refused.isNotEmpty()
}

/**
 * Import from Obsidian: reads `.obsidian/daily-notes.json` (`folder`, `template`, `format`) over WebDAV and applies
 * it through the [DailySettings] setters, so the same rules apply as in Settings. A missing key keeps the current
 * value; a refused value is kept as before and reported. Runs once automatically after the first successful Sync
 * ([runOnce]) and on demand from Settings ([run]).
 */
class ObsidianDailyImport(
    private val dav: DavClient,
    private val settings: DailySettings,
) {
    private val once = Mutex()

    suspend fun run(): ImportReport {
        val text =
            when (val result = dav.get(VaultPath.parse(OBSIDIAN_DAILY_NOTES)!!)) {
                is ApiResult.Success -> {
                    (result.value as? DavGet.Body)?.bytes?.decodeToString() ?: return ImportReport(notFound = true)
                }

                is ApiResult.Failure -> {
                    return if (result.error == ApiError.NotFound) ImportReport(notFound = true) else ImportReport(error = result.error)
                }
            }
        val values = parse(text) ?: return ImportReport(notFound = true)
        return apply(values)
    }

    /**
     * The automatic Import: runs [run] when `daily.import-done` is not set, then sets it whatever the result.
     * Returns null when it already ran. Concurrent calls run it once.
     */
    suspend fun runOnce(): ImportReport? =
        once.withLock {
            if (settings.importDone()) return@withLock null
            try {
                run()
            } finally {
                settings.setImportDone()
            }
        }

    private fun apply(values: Map<String, String>): ImportReport {
        val read = linkedMapOf<String, String>()
        val refused = mutableListOf<Refusal>()
        values[IMPORT_FOLDER]?.let { folder ->
            when (val result = settings.setFolder(folder)) {
                is FolderResult.Saved -> read[IMPORT_FOLDER] = result.path
                is FolderResult.Refused -> refused += Refusal.Folder(folder, result.error)
            }
        }
        values[IMPORT_TEMPLATE]?.let { template ->
            settings.setTemplate(template)
            read[IMPORT_TEMPLATE] = settings.template()
        }
        values[IMPORT_FORMAT]?.let { format ->
            when (val result = settings.setFormat(format)) {
                is FormatResult.Saved -> read[IMPORT_FORMAT] = result.format
                else -> refused += Refusal.Format(format, result)
            }
        }
        return ImportReport(read, refused)
    }

    /** The string values of the keys Import reads, or null when [text] isn't a JSON object. */
    private fun parse(text: String): Map<String, String>? {
        val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        return listOf(IMPORT_FOLDER, IMPORT_TEMPLATE, IMPORT_FORMAT)
            .mapNotNull { key -> (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let { key to it } }
            .toMap()
    }
}
