package app.citti.core.appearance

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.citti.core.db.CittiDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

const val KEY_APPEARANCE_THEME = "appearance.theme"
const val KEY_APPEARANCE_MODE = "appearance.mode"
const val KEY_APPEARANCE_ACCENT = "appearance.accent"
const val KEY_APPEARANCE_CUSTOM = "appearance.custom"

private const val APPEARANCE_PREFIX = "appearance."

/** The selected theme: one of the presets, or the Custom theme. Stored by [name]. */
enum class ThemeChoice { SIGNAL, PAPER, INDEX, CUSTOM }

/** Light or dark, or follow the phone. Stored by [name]. */
enum class ModeChoice { SYSTEM, LIGHT, DARK }

/** Where the accent colors come from: the theme, or the wallpaper (Dynamic). Stored by [name]. */
enum class AccentSource { THEME, DYNAMIC }

/** Everything that decides how the app looks. [custom] is null while the Custom theme slot is empty. */
data class Appearance(
    val theme: ThemeChoice = ThemeChoice.SIGNAL,
    val mode: ModeChoice = ModeChoice.SYSTEM,
    val accent: AccentSource = AccentSource.THEME,
    val custom: CustomTheme? = null,
)

/**
 * Reads and writes the [Appearance] in the `setting` table (device-local). An unknown stored value falls back to
 * the default; an unreadable `appearance.custom` counts as an empty slot.
 */
class AppearanceSettings(
    private val db: CittiDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val queries = db.settingQueries

    /** Called after every write (hook for the widget refresh). */
    var onChanged: (() -> Unit)? = null

    /** One indexed query; cheap enough to run before the first frame. */
    fun current(): Appearance = parse(queries.selectSettingsWithPrefix(APPEARANCE_PREFIX).executeAsList().associate { it.key to it.value_ })

    fun flow(): Flow<Appearance> =
        queries
            .selectSettingsWithPrefix(APPEARANCE_PREFIX)
            .asFlow()
            .mapToList(dispatcher)
            .map { rows -> parse(rows.associate { it.key to it.value_ }) }
            .distinctUntilChanged()

    fun setTheme(theme: ThemeChoice) = write { queries.upsertSetting(KEY_APPEARANCE_THEME, theme.name) }

    fun setMode(mode: ModeChoice) = write { queries.upsertSetting(KEY_APPEARANCE_MODE, mode.name) }

    fun setAccent(accent: AccentSource) = write { queries.upsertSetting(KEY_APPEARANCE_ACCENT, accent.name) }

    fun setCustom(custom: CustomTheme?) = write { storeCustom(custom) }

    /** Stores [custom] and selects Custom in one transaction, with one [onChanged]. */
    fun setCustomAndSelect(custom: CustomTheme) =
        write {
            storeCustom(custom)
            queries.upsertSetting(KEY_APPEARANCE_THEME, ThemeChoice.CUSTOM.name)
        }

    /** Read-modify-write of the Custom theme in one transaction; an empty slot starts from [seedBase]. */
    fun updateCustom(
        seedBase: BaseTheme = BaseTheme.SIGNAL,
        change: (CustomTheme) -> CustomTheme,
    ) = write { storeCustom(change(readCustom() ?: CustomTheme(seedBase))) }

    /** The Custom theme, or, when the slot is empty, a new one with no overrides based on [base] (stored and selected). */
    fun selectCustomOrSeed(base: BaseTheme): CustomTheme {
        var result: CustomTheme? = null
        write {
            val existing = readCustom()
            result = existing ?: CustomTheme(base).also { storeCustom(it) }
            queries.upsertSetting(KEY_APPEARANCE_THEME, ThemeChoice.CUSTOM.name)
        }
        return result!!
    }

    private fun write(block: () -> Unit) {
        db.transaction { block() }
        onChanged?.invoke()
    }

    private fun storeCustom(custom: CustomTheme?) {
        if (custom == null) {
            queries.deleteSetting(KEY_APPEARANCE_CUSTOM)
        } else {
            queries.upsertSetting(KEY_APPEARANCE_CUSTOM, ThemeFileCodec.encode(custom))
        }
    }

    private fun readCustom(): CustomTheme? = decodeCustom(queries.selectSetting(KEY_APPEARANCE_CUSTOM).executeAsOneOrNull())

    private fun parse(rows: Map<String, String>): Appearance =
        Appearance(
            theme = ThemeChoice.entries.firstOrNull { it.name == rows[KEY_APPEARANCE_THEME] } ?: ThemeChoice.SIGNAL,
            mode = ModeChoice.entries.firstOrNull { it.name == rows[KEY_APPEARANCE_MODE] } ?: ModeChoice.SYSTEM,
            accent = AccentSource.entries.firstOrNull { it.name == rows[KEY_APPEARANCE_ACCENT] } ?: AccentSource.THEME,
            custom = decodeCustom(rows[KEY_APPEARANCE_CUSTOM]),
        )

    private fun decodeCustom(raw: String?): CustomTheme? = raw?.let { (ThemeFileCodec.decode(it) as? DecodeResult.Ok)?.theme }
}
