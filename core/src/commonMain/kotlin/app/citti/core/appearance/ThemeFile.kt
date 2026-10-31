package app.citti.core.appearance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** The three preset themes a Custom theme can be based on. */
enum class BaseTheme { SIGNAL, PAPER, INDEX }

/** The 20 color tokens of a theme, with their kebab-case key in the Theme file. */
enum class ColorToken(
    val key: String,
) {
    BG("bg"),
    SURFACE("surface"),
    SURFACE_RAISED("surface-raised"),
    LINE("line"),
    INK("ink"),
    INK_MUTED("ink-muted"),
    INK_FAINT("ink-faint"),
    ACCENT("accent"),
    ON_ACCENT("on-accent"),
    ACCENT_INK("accent-ink"),
    ACCENT_SOFT("accent-soft"),
    FOCUS("focus"),
    FAVORITE("favorite"),
    CONFLICT("conflict"),
    ON_CONFLICT("on-conflict"),
    DANGER("danger"),
    OK("ok"),
    HIGHLIGHT("highlight"),
    ON_HIGHLIGHT("on-highlight"),
    SCRIM("scrim"),
}

/** The five font roles a Custom theme can override. */
enum class FontRole(
    val key: String,
) {
    UI("font-ui"),
    DISPLAY("font-display"),
    READING("font-reading"),
    CODE("font-code"),
    META("font-meta"),
}

/** The faces a font role can use: the bundled ones, or the system font. */
enum class FontFace(
    val id: String,
) {
    SPACE_GROTESK("space-grotesk"),
    SPACE_MONO("space-mono"),
    NEWSREADER("newsreader"),
    PUBLIC_SANS("public-sans"),
    IBM_PLEX_SANS("ibm-plex-sans"),
    IBM_PLEX_MONO("ibm-plex-mono"),
    SYSTEM("system"),
}

/** Scales the base's row height and gutter by 0.85 / 1 / 1.15. */
enum class Density(
    val id: String,
    val factor: Double,
) {
    COMPACT("compact", 0.85),
    DEFAULT("default", 1.0),
    COMFORTABLE("comfortable", 1.15),
}

/** Sets the three radii together: Square 0 / 0 / 0, Soft 6 / 6 / 6, Round 24 / 14 / 999. */
enum class Corners(
    val id: String,
    val control: Int,
    val media: Int,
    val newNote: Int,
) {
    SQUARE("square", 0, 0, 0),
    SOFT("soft", 6, 6, 6),
    ROUND("round", 24, 14, 999),
}

/** Every override of a Custom theme that is not a color. A null or empty member means the base's own value. */
data class RoleOverrides(
    val fonts: Map<FontRole, FontFace> = emptyMap(),
    /** 0.85..1.30. */
    val textScale: Double? = null,
    val density: Density? = null,
    val corners: Corners? = null,
    /** Direct dp values; they win over [density] and [corners]. */
    val rowMin: Int? = null,
    val gutter: Int? = null,
    val radiusControl: Int? = null,
    val radiusMedia: Int? = null,
    val radiusNew: Int? = null,
    /** True = capitals with tracking, false = sentence case. */
    val labelCapitals: Boolean? = null,
    /** In em. */
    val labelTracking: Double? = null,
) {
    /** The number of overridden roles (each font role counts as one). */
    val count: Int
        get() =
            fonts.size +
                listOf(textScale, density, corners, rowMin, gutter, radiusControl, radiusMedia, radiusNew, labelCapitals, labelTracking)
                    .count { it != null }
}

/**
 * A Custom theme: a [base] preset plus sparse overrides. Colors are ARGB values (`0xAARRGGBB`), one map for the
 * light variant and one for the dark variant.
 */
data class CustomTheme(
    val base: BaseTheme,
    val light: Map<ColorToken, Long> = emptyMap(),
    val dark: Map<ColorToken, Long> = emptyMap(),
    val roles: RoleOverrides = RoleOverrides(),
) {
    val overrideCount: Int get() = light.size + dark.size + roles.count
}

sealed interface DecodeResult {
    data class Ok(
        val theme: CustomTheme,
    ) : DecodeResult

    /** The text is not a Theme file; [message] says why. */
    data class NotAThemeFile(
        val message: String,
    ) : DecodeResult
}

/** The largest Theme file we read; a larger one is refused (M9 audit). */
const val MAX_THEME_FILE_BYTES = 64 * 1024

const val THEME_FILE_SCHEMA = 1

/** Reads and writes the Theme file (see ADR 0004). Lenient on read, stable on write. */
object ThemeFileCodec {
    private val json = Json { prettyPrint = true }

    fun encode(theme: CustomTheme): String {
        val root =
            buildJsonObject {
                put("schema", THEME_FILE_SCHEMA)
                put("base", theme.base.name.lowercase())
                put(
                    "overrides",
                    buildJsonObject {
                        put("light", colorsObject(theme.light))
                        put("dark", colorsObject(theme.dark))
                        put("roles", rolesObject(theme.roles))
                    },
                )
            }
        return json.encodeToString(JsonElement.serializer(), root)
    }

    fun decode(text: String): DecodeResult {
        if (text.length > MAX_THEME_FILE_BYTES) return DecodeResult.NotAThemeFile("file too large")
        val root =
            try {
                Json.parseToJsonElement(text)
            } catch (_: Exception) {
                return DecodeResult.NotAThemeFile("not JSON")
            }
        if (root !is JsonObject) return DecodeResult.NotAThemeFile("not a JSON object")
        val schema = (root["schema"] as? JsonPrimitive)?.intOrNull
        if (schema == null) return DecodeResult.NotAThemeFile("schema missing")
        if (schema != THEME_FILE_SCHEMA) return DecodeResult.NotAThemeFile("unsupported schema $schema")
        val overrides = root["overrides"] as? JsonObject ?: return DecodeResult.NotAThemeFile("overrides missing")
        val base =
            BaseTheme.entries.firstOrNull { it.name.equals((root["base"] as? JsonPrimitive)?.contentOrNull, ignoreCase = true) }
                ?: BaseTheme.SIGNAL
        return DecodeResult.Ok(
            CustomTheme(
                base = base,
                light = readColors(overrides["light"]),
                dark = readColors(overrides["dark"]),
                roles = readRoles(overrides["roles"]),
            ),
        )
    }

    private fun colorsObject(colors: Map<ColorToken, Long>): JsonObject =
        buildJsonObject {
            for (token in ColorToken.entries) colors[token]?.let { put(token.key, formatHex(it)) }
        }

    private fun rolesObject(r: RoleOverrides): JsonObject =
        buildJsonObject {
            for (role in FontRole.entries) r.fonts[role]?.let { put(role.key, it.id) }
            r.textScale?.let { put("text-scale", it) }
            r.density?.let { put("density", it.id) }
            r.corners?.let { put("corners", it.id) }
            r.rowMin?.let { put("row-min", it) }
            r.gutter?.let { put("gutter", it) }
            r.radiusControl?.let { put("radius-control", it) }
            r.radiusMedia?.let { put("radius-media", it) }
            r.radiusNew?.let { put("radius-new", it) }
            r.labelCapitals?.let { put("label-case", if (it) "upper" else "sentence") }
            r.labelTracking?.let { put("label-tracking", it) }
        }

    private fun readColors(element: JsonElement?): Map<ColorToken, Long> {
        val obj = element as? JsonObject ?: return emptyMap()
        val out = LinkedHashMap<ColorToken, Long>()
        for (token in ColorToken.entries) {
            val raw = (obj[token.key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            parseHex(raw)?.let { out[token] = it }
        }
        return out
    }

    private fun readRoles(element: JsonElement?): RoleOverrides {
        val obj = element as? JsonObject ?: return RoleOverrides()

        fun prim(key: String): JsonPrimitive? = (obj[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }

        fun int(
            key: String,
            range: IntRange,
        ): Int? = prim(key)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in range }

        val fonts = LinkedHashMap<FontRole, FontFace>()
        for (role in FontRole.entries) {
            val id = prim(role.key)?.contentOrNull
            FontFace.entries.firstOrNull { it.id == id }?.let { fonts[role] = it }
        }
        return RoleOverrides(
            fonts = fonts,
            textScale =
                prim(
                    "text-scale",
                )?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }?.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE),
            density = Density.entries.firstOrNull { it.id == prim("density")?.contentOrNull },
            corners = Corners.entries.firstOrNull { it.id == prim("corners")?.contentOrNull },
            rowMin = int("row-min", 32..96),
            gutter = int("gutter", 8..40),
            radiusControl = int("radius-control", 0..999),
            radiusMedia = int("radius-media", 0..999),
            radiusNew = int("radius-new", 0..999),
            labelCapitals =
                when (prim("label-case")?.contentOrNull) {
                    "upper" -> true
                    "sentence" -> false
                    else -> null
                },
            labelTracking = prim("label-tracking")?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it in 0.0..0.3 },
        )
    }

    /** `#RRGGBB` when opaque, `#RRGGBBAA` otherwise. */
    fun formatHex(argb: Long): String {
        val a = (argb shr 24 and 0xFF).toInt()
        val rgb = (argb and 0xFFFFFF).toString(16).padStart(6, '0')
        return if (a == 0xFF) "#$rgb" else "#$rgb" + a.toString(16).padStart(2, '0')
    }

    /** `#RRGGBB` or `#RRGGBBAA` (any case) to ARGB, or null. */
    fun parseHex(raw: String): Long? {
        if (!raw.startsWith("#") || (raw.length != 7 && raw.length != 9)) return null
        val digits = raw.substring(1)
        if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val rgb = digits.substring(0, 6).toLong(16)
        val a = if (digits.length == 8) digits.substring(6).toLong(16) else 0xFF
        return (a shl 24) or rgb
    }
}

const val MIN_TEXT_SCALE = 0.85
const val MAX_TEXT_SCALE = 1.30
