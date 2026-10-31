package app.citti.ui.appearance

import app.citti.core.appearance.ColorToken
import app.citti.core.appearance.Corners
import app.citti.core.appearance.Density
import app.citti.core.appearance.FontFace
import app.citti.core.appearance.FontRole
import app.citti.ui.resources.Res
import app.citti.ui.resources.font_system
import app.citti.ui.resources.theme_corners_round
import app.citti.ui.resources.theme_corners_soft
import app.citti.ui.resources.theme_corners_square
import app.citti.ui.resources.theme_density_comfortable
import app.citti.ui.resources.theme_density_compact
import app.citti.ui.resources.theme_density_default
import app.citti.ui.resources.theme_font_code
import app.citti.ui.resources.theme_font_display
import app.citti.ui.resources.theme_font_meta
import app.citti.ui.resources.theme_font_reading
import app.citti.ui.resources.theme_font_ui
import app.citti.ui.resources.theme_group_accent
import app.citti.ui.resources.theme_group_highlight
import app.citti.ui.resources.theme_group_overlay
import app.citti.ui.resources.theme_group_status
import app.citti.ui.resources.theme_group_surfaces
import app.citti.ui.resources.theme_group_text
import app.citti.ui.resources.theme_token_accent
import app.citti.ui.resources.theme_token_accent_ink
import app.citti.ui.resources.theme_token_accent_soft
import app.citti.ui.resources.theme_token_bg
import app.citti.ui.resources.theme_token_conflict
import app.citti.ui.resources.theme_token_danger
import app.citti.ui.resources.theme_token_favorite
import app.citti.ui.resources.theme_token_focus
import app.citti.ui.resources.theme_token_highlight
import app.citti.ui.resources.theme_token_ink
import app.citti.ui.resources.theme_token_ink_faint
import app.citti.ui.resources.theme_token_ink_muted
import app.citti.ui.resources.theme_token_line
import app.citti.ui.resources.theme_token_ok
import app.citti.ui.resources.theme_token_on_accent
import app.citti.ui.resources.theme_token_on_conflict
import app.citti.ui.resources.theme_token_on_highlight
import app.citti.ui.resources.theme_token_scrim
import app.citti.ui.resources.theme_token_surface
import app.citti.ui.resources.theme_token_surface_raised
import org.jetbrains.compose.resources.StringResource

/** The twenty color tokens in six groups, in the order the editor lists them. */
val COLOR_GROUPS: List<Pair<StringResource, List<ColorToken>>> =
    listOf(
        Res.string.theme_group_surfaces to listOf(ColorToken.BG, ColorToken.SURFACE, ColorToken.SURFACE_RAISED, ColorToken.LINE),
        Res.string.theme_group_text to listOf(ColorToken.INK, ColorToken.INK_MUTED, ColorToken.INK_FAINT),
        Res.string.theme_group_accent to
            listOf(ColorToken.ACCENT, ColorToken.ON_ACCENT, ColorToken.ACCENT_INK, ColorToken.ACCENT_SOFT, ColorToken.FOCUS),
        Res.string.theme_group_status to
            listOf(ColorToken.FAVORITE, ColorToken.CONFLICT, ColorToken.ON_CONFLICT, ColorToken.DANGER, ColorToken.OK),
        Res.string.theme_group_highlight to listOf(ColorToken.HIGHLIGHT, ColorToken.ON_HIGHLIGHT),
        Res.string.theme_group_overlay to listOf(ColorToken.SCRIM),
    )

/** The tokens Dynamic replaces. */
val DYNAMIC_TOKENS = setOf(ColorToken.ACCENT, ColorToken.ON_ACCENT, ColorToken.ACCENT_INK, ColorToken.ACCENT_SOFT)

/** The tokens whose alpha is edited (`#RRGGBBAA`). */
val ALPHA_TOKENS = setOf(ColorToken.SCRIM, ColorToken.HIGHLIGHT)

fun ColorToken.label(): StringResource =
    when (this) {
        ColorToken.BG -> Res.string.theme_token_bg
        ColorToken.SURFACE -> Res.string.theme_token_surface
        ColorToken.SURFACE_RAISED -> Res.string.theme_token_surface_raised
        ColorToken.LINE -> Res.string.theme_token_line
        ColorToken.INK -> Res.string.theme_token_ink
        ColorToken.INK_MUTED -> Res.string.theme_token_ink_muted
        ColorToken.INK_FAINT -> Res.string.theme_token_ink_faint
        ColorToken.ACCENT -> Res.string.theme_token_accent
        ColorToken.ON_ACCENT -> Res.string.theme_token_on_accent
        ColorToken.ACCENT_INK -> Res.string.theme_token_accent_ink
        ColorToken.ACCENT_SOFT -> Res.string.theme_token_accent_soft
        ColorToken.FOCUS -> Res.string.theme_token_focus
        ColorToken.FAVORITE -> Res.string.theme_token_favorite
        ColorToken.CONFLICT -> Res.string.theme_token_conflict
        ColorToken.ON_CONFLICT -> Res.string.theme_token_on_conflict
        ColorToken.DANGER -> Res.string.theme_token_danger
        ColorToken.OK -> Res.string.theme_token_ok
        ColorToken.HIGHLIGHT -> Res.string.theme_token_highlight
        ColorToken.ON_HIGHLIGHT -> Res.string.theme_token_on_highlight
        ColorToken.SCRIM -> Res.string.theme_token_scrim
    }

fun FontRole.label(): StringResource =
    when (this) {
        FontRole.UI -> Res.string.theme_font_ui
        FontRole.DISPLAY -> Res.string.theme_font_display
        FontRole.READING -> Res.string.theme_font_reading
        FontRole.CODE -> Res.string.theme_font_code
        FontRole.META -> Res.string.theme_font_meta
    }

/** The face's display name; the bundled ones are proper names (not translated), System is. */
fun FontFace.displayName(): String? =
    when (this) {
        FontFace.SPACE_GROTESK -> "Space Grotesk"
        FontFace.SPACE_MONO -> "Space Mono"
        FontFace.NEWSREADER -> "Newsreader"
        FontFace.PUBLIC_SANS -> "Public Sans"
        FontFace.IBM_PLEX_SANS -> "IBM Plex Sans"
        FontFace.IBM_PLEX_MONO -> "IBM Plex Mono"
        FontFace.SYSTEM -> null
    }

val FontFace.systemLabel: StringResource get() = Res.string.font_system

fun Density.label(): StringResource =
    when (this) {
        Density.COMPACT -> Res.string.theme_density_compact
        Density.DEFAULT -> Res.string.theme_density_default
        Density.COMFORTABLE -> Res.string.theme_density_comfortable
    }

fun Corners.label(): StringResource =
    when (this) {
        Corners.SQUARE -> Res.string.theme_corners_square
        Corners.SOFT -> Res.string.theme_corners_soft
        Corners.ROUND -> Res.string.theme_corners_round
    }
