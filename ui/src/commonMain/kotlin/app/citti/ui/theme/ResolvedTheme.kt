package app.citti.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.citti.core.appearance.AccentSource
import app.citti.core.appearance.Appearance
import app.citti.core.appearance.BaseTheme
import app.citti.core.appearance.ColorToken
import app.citti.core.appearance.CustomTheme
import app.citti.core.appearance.FontFace
import app.citti.core.appearance.FontRole
import app.citti.core.appearance.ModeChoice
import app.citti.core.appearance.ThemeChoice
import kotlin.math.roundToInt

/** Capitals with 0.14 em tracking: Signal's label style, used by the Capital labels switch. */
const val CAPITAL_LABEL_TRACKING = 0.14

/** An [Appearance] turned into what the components read. */
@Immutable
class ResolvedTheme(
    val colors: NotesColors,
    val shape: NotesShape,
    val typography: CittiTypography,
    val family: ThemeFamily,
    val dark: Boolean,
    val textScale: Float,
)

fun BaseTheme.toFamily(): ThemeFamily =
    when (this) {
        BaseTheme.SIGNAL -> ThemeFamily.Signal
        BaseTheme.PAPER -> ThemeFamily.Paper
        BaseTheme.INDEX -> ThemeFamily.Index
    }

/** The preset (or the base of the Custom theme) the [Appearance] resolves from; an empty Custom slot is Signal. */
fun Appearance.baseTheme(): BaseTheme =
    when (theme) {
        ThemeChoice.SIGNAL -> BaseTheme.SIGNAL
        ThemeChoice.PAPER -> BaseTheme.PAPER
        ThemeChoice.INDEX -> BaseTheme.INDEX
        ThemeChoice.CUSTOM -> custom?.base ?: BaseTheme.SIGNAL
    }

/** [Appearance.mode] with System resolved against [systemDark]. */
fun Appearance.isDark(systemDark: Boolean): Boolean =
    when (mode) {
        ModeChoice.SYSTEM -> systemDark
        ModeChoice.LIGHT -> false
        ModeChoice.DARK -> true
    }

/** The `FontResolver` key of a bundled face. */
fun FontFace.resolverKey(): String? =
    when (this) {
        FontFace.SPACE_GROTESK -> "signal"
        FontFace.SPACE_MONO -> "signal-mono"
        FontFace.NEWSREADER -> "paper-serif"
        FontFace.PUBLIC_SANS -> "paper-sans"
        FontFace.IBM_PLEX_SANS -> "index"
        FontFace.IBM_PLEX_MONO -> "index-mono"
        FontFace.SYSTEM -> null
    }

fun resolveTheme(
    appearance: Appearance,
    systemDark: Boolean,
    dynamic: DynamicAccent?,
    fonts: FontResolver,
): ResolvedTheme {
    val base = appearance.baseTheme()
    val family = base.toFamily()
    val dark = appearance.isDark(systemDark)
    val custom = appearance.custom?.takeIf { appearance.theme == ThemeChoice.CUSTOM }
    return ResolvedTheme(
        colors = resolveColors(family, dark, custom, appearance.accent, dynamic),
        shape = resolveShape(family, custom),
        typography = resolveTypography(family, custom, fonts),
        family = family,
        dark = dark,
        textScale = custom?.roles?.textScale?.toFloat() ?: 1f,
    )
}

internal fun resolveColors(
    family: ThemeFamily,
    dark: Boolean,
    custom: CustomTheme?,
    accentSource: AccentSource,
    dynamic: DynamicAccent?,
): NotesColors {
    var c = notesColors(family, dark).copy(isDark = dark)
    val overrides = if (dark) custom?.dark else custom?.light
    overrides?.forEach { (token, argb) -> c = c.with(token, Color(argb)) }
    if (accentSource == AccentSource.DYNAMIC && dynamic != null) {
        c =
            c.copy(
                accent = Color(dynamic.accent),
                onAccent = Color(dynamic.onAccent),
                accentInk = Color(dynamic.accentInk),
                accentSoft = Color(dynamic.accentSoft),
            )
    }
    return c
}

/** The color of [token]. */
fun NotesColors.get(token: ColorToken): Color =
    when (token) {
        ColorToken.BG -> bg
        ColorToken.SURFACE -> surface
        ColorToken.SURFACE_RAISED -> surfaceRaised
        ColorToken.LINE -> line
        ColorToken.INK -> ink
        ColorToken.INK_MUTED -> inkMuted
        ColorToken.INK_FAINT -> inkFaint
        ColorToken.ACCENT -> accent
        ColorToken.ON_ACCENT -> onAccent
        ColorToken.ACCENT_INK -> accentInk
        ColorToken.ACCENT_SOFT -> accentSoft
        ColorToken.FOCUS -> focus
        ColorToken.FAVORITE -> favorite
        ColorToken.CONFLICT -> conflict
        ColorToken.ON_CONFLICT -> onConflict
        ColorToken.DANGER -> danger
        ColorToken.OK -> ok
        ColorToken.HIGHLIGHT -> highlight
        ColorToken.ON_HIGHLIGHT -> onHighlight
        ColorToken.SCRIM -> scrim
    }

private fun NotesColors.with(
    token: ColorToken,
    color: Color,
): NotesColors =
    when (token) {
        ColorToken.BG -> copy(bg = color)
        ColorToken.SURFACE -> copy(surface = color)
        ColorToken.SURFACE_RAISED -> copy(surfaceRaised = color)
        ColorToken.LINE -> copy(line = color)
        ColorToken.INK -> copy(ink = color)
        ColorToken.INK_MUTED -> copy(inkMuted = color)
        ColorToken.INK_FAINT -> copy(inkFaint = color)
        ColorToken.ACCENT -> copy(accent = color)
        ColorToken.ON_ACCENT -> copy(onAccent = color)
        ColorToken.ACCENT_INK -> copy(accentInk = color)
        ColorToken.ACCENT_SOFT -> copy(accentSoft = color)
        ColorToken.FOCUS -> copy(focus = color)
        ColorToken.FAVORITE -> copy(favorite = color)
        ColorToken.CONFLICT -> copy(conflict = color)
        ColorToken.ON_CONFLICT -> copy(onConflict = color)
        ColorToken.DANGER -> copy(danger = color)
        ColorToken.OK -> copy(ok = color)
        ColorToken.HIGHLIGHT -> copy(highlight = color)
        ColorToken.ON_HIGHLIGHT -> copy(onHighlight = color)
        ColorToken.SCRIM -> copy(scrim = color)
    }

private fun resolveShape(
    family: ThemeFamily,
    custom: CustomTheme?,
): NotesShape {
    val base = notesShape(family)
    val r = custom?.roles ?: return base
    val corners = r.corners
    val density = r.density

    fun scaled(value: androidx.compose.ui.unit.Dp) = ((value.value * (density?.factor ?: 1.0)).roundToInt()).dp
    return NotesShape(
        control = (r.radiusControl ?: corners?.control)?.dp ?: base.control,
        media = (r.radiusMedia ?: corners?.media)?.dp ?: base.media,
        newNote = (r.radiusNew ?: corners?.newNote)?.dp ?: base.newNote,
        rowMin = r.rowMin?.dp ?: scaled(base.rowMin),
        gutter = r.gutter?.dp ?: scaled(base.gutter),
        labelsUppercase = r.labelCapitals ?: base.labelsUppercase,
    )
}

private fun resolveTypography(
    family: ThemeFamily,
    custom: CustomTheme?,
    fonts: FontResolver,
): CittiTypography {
    val base = cittiTypography(family, fonts)
    val r = custom?.roles ?: return base

    fun family(role: FontRole): FontFamily? =
        r.fonts[role]?.let { face -> face.resolverKey()?.let { fonts.fontFor(it) } ?: FontFamily.Default }

    fun TextStyle.with(role: FontRole): TextStyle = family(role)?.let { copy(fontFamily = it) } ?: this

    val tracking =
        r.labelTracking ?: when (r.labelCapitals) {
            true -> CAPITAL_LABEL_TRACKING
            false -> 0.0
            null -> null
        }
    val label = base.label.with(FontRole.UI)
    return CittiTypography(
        title = base.title.with(FontRole.DISPLAY),
        rowTitle = base.rowTitle.with(FontRole.DISPLAY),
        body = base.body.with(FontRole.READING),
        ui = base.ui.with(FontRole.UI),
        meta = base.meta.with(FontRole.META),
        label = if (tracking != null) label.copy(letterSpacing = tracking.em) else label,
        editor = base.editor.with(FontRole.CODE),
    )
}

/** The `bg` color of the [Appearance], without composing (the window background before the first frame). */
fun Appearance.background(systemDark: Boolean): Color {
    val base = baseTheme()
    val custom = custom?.takeIf { theme == ThemeChoice.CUSTOM }
    return resolveColors(base.toFamily(), isDark(systemDark), custom, AccentSource.THEME, null).bg
}
