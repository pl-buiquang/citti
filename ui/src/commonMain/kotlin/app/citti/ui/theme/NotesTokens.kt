// citti Design System — generated from tokens.json
package app.citti.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class ThemeFamily { Signal, Paper, Index }
enum class ThemeMode { System, Light, Dark }

@Immutable
data class NotesColors(
    val bg: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val line: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val accent: Color,
    val onAccent: Color,
    val accentInk: Color,
    val accentSoft: Color,
    val focus: Color,
    val favorite: Color,
    val conflict: Color,
    val onConflict: Color,
    val danger: Color,
    val ok: Color,
    val highlight: Color,
    val onHighlight: Color,
    val scrim: Color,
    val isDark: Boolean,
)

val signalDarkColors = NotesColors(
    bg = Color(0xFF000000),
    surface = Color(0xFF121211),
    surfaceRaised = Color(0xFF1C1C1A),
    line = Color(0xFF262624),
    ink = Color(0xFFF5F5F2),
    inkMuted = Color(0xFF9A9A94),
    inkFaint = Color(0xFF6E6E68),
    accent = Color(0xFFFFB224),
    onAccent = Color(0xFF1A0F00),
    accentInk = Color(0xFFFFB224),
    accentSoft = Color(0xFF2A1E05),
    focus = Color(0xFFFFB224),
    favorite = Color(0xFFFFB224),
    conflict = Color(0xFFFF6B8B),
    onConflict = Color(0xFF1F0008),
    danger = Color(0xFFFF5449),
    ok = Color(0xFF7FD49A),
    highlight = Color(0xFF4D3500),
    onHighlight = Color(0xFFFFE3A8),
    scrim = Color(0x99000000),
    isDark = true,
)

val signalLightColors = NotesColors(
    bg = Color(0xFFF4F4EF),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFFFFFFF),
    line = Color(0xFFD9D9D1),
    ink = Color(0xFF0A0A0A),
    inkMuted = Color(0xFF5D5D57),
    inkFaint = Color(0xFF85857D),
    accent = Color(0xFFF5A300),
    onAccent = Color(0xFF1A0F00),
    accentInk = Color(0xFF8A5200),
    accentSoft = Color(0xFFFFE7B8),
    focus = Color(0xFF8A5200),
    favorite = Color(0xFF8A5200),
    conflict = Color(0xFFB3123F),
    onConflict = Color(0xFFFFFFFF),
    danger = Color(0xFFBA1A1A),
    ok = Color(0xFF1F7A3D),
    highlight = Color(0xFFFFE08F),
    onHighlight = Color(0xFF0A0A0A),
    scrim = Color(0x520A0A0A),
    isDark = false,
)

val paperLightColors = NotesColors(
    bg = Color(0xFFF5F1E8),
    surface = Color(0xFFFBF8F1),
    surfaceRaised = Color(0xFFFFFFFF),
    line = Color(0xFFDDD5C5),
    ink = Color(0xFF1F1C17),
    inkMuted = Color(0xFF5E574B),
    inkFaint = Color(0xFF8E8676),
    accent = Color(0xFF2C4A7A),
    onAccent = Color(0xFFFFFFFF),
    accentInk = Color(0xFF2C4A7A),
    accentSoft = Color(0xFFE3E6EE),
    focus = Color(0xFF2C4A7A),
    favorite = Color(0xFF9A4A1F),
    conflict = Color(0xFF9A4A00),
    onConflict = Color(0xFFFFFFFF),
    danger = Color(0xFFA3261B),
    ok = Color(0xFF3D7A4A),
    highlight = Color(0xFFF3DFA2),
    onHighlight = Color(0xFF1F1C17),
    scrim = Color(0x521F1C17),
    isDark = false,
)

val paperDarkColors = NotesColors(
    bg = Color(0xFF1B1915),
    surface = Color(0xFF25221D),
    surfaceRaised = Color(0xFF2E2A24),
    line = Color(0xFF3A352D),
    ink = Color(0xFFEDE7DB),
    inkMuted = Color(0xFFB3AA99),
    inkFaint = Color(0xFF7D7568),
    accent = Color(0xFF9DB7E8),
    onAccent = Color(0xFF10203D),
    accentInk = Color(0xFF9DB7E8),
    accentSoft = Color(0xFF2C3446),
    focus = Color(0xFF9DB7E8),
    favorite = Color(0xFFE3A078),
    conflict = Color(0xFFF0A868),
    onConflict = Color(0xFF2A1400),
    danger = Color(0xFFFFB4A8),
    ok = Color(0xFF8FC79A),
    highlight = Color(0xFF5A4A1E),
    onHighlight = Color(0xFFF7E7B5),
    scrim = Color(0x8C000000),
    isDark = true,
)

val indexDarkColors = NotesColors(
    bg = Color(0xFF0E1013),
    surface = Color(0xFF16191E),
    surfaceRaised = Color(0xFF1D2128),
    line = Color(0xFF262A31),
    ink = Color(0xFFE6E8EB),
    inkMuted = Color(0xFF9AA1AC),
    inkFaint = Color(0xFF6F7680),
    accent = Color(0xFF7AA2FF),
    onAccent = Color(0xFF0B1230),
    accentInk = Color(0xFF7AA2FF),
    accentSoft = Color(0xFF1B2540),
    focus = Color(0xFF7AA2FF),
    favorite = Color(0xFFFFC34D),
    conflict = Color(0xFFFF9E5E),
    onConflict = Color(0xFF2A1000),
    danger = Color(0xFFFF6B6B),
    ok = Color(0xFF7FD49A),
    highlight = Color(0xFF3D3410),
    onHighlight = Color(0xFFFFE38A),
    scrim = Color(0x99000000),
    isDark = true,
)

val indexLightColors = NotesColors(
    bg = Color(0xFFFFFFFF),
    surface = Color(0xFFF3F4F6),
    surfaceRaised = Color(0xFFFFFFFF),
    line = Color(0xFFE3E5E8),
    ink = Color(0xFF111316),
    inkMuted = Color(0xFF5A616B),
    inkFaint = Color(0xFF858B94),
    accent = Color(0xFF1F4FD1),
    onAccent = Color(0xFFFFFFFF),
    accentInk = Color(0xFF1F4FD1),
    accentSoft = Color(0xFFE7EDFC),
    focus = Color(0xFF1F4FD1),
    favorite = Color(0xFF9A6400),
    conflict = Color(0xFFB04A00),
    onConflict = Color(0xFFFFFFFF),
    danger = Color(0xFFC62828),
    ok = Color(0xFF1F7A3D),
    highlight = Color(0xFFFFF0A8),
    onHighlight = Color(0xFF111316),
    scrim = Color(0x52111316),
    isDark = false,
)

fun notesColors(family: ThemeFamily, dark: Boolean): NotesColors = when (family) {
    ThemeFamily.Signal -> if (dark) signalDarkColors else signalLightColors
    ThemeFamily.Paper -> if (dark) paperDarkColors else paperLightColors
    ThemeFamily.Index -> if (dark) indexDarkColors else indexLightColors
}

object NotesSpacing {
    val space1 = 4.dp
    val space2 = 8.dp
    val space3 = 12.dp
    val space4 = 16.dp
    val space5 = 20.dp
    val space6 = 24.dp
    val space8 = 32.dp
    val space10 = 40.dp
}

object NotesRadius {
    val radiusNone = 0.dp
    val radiusSm = 6.dp
    val radiusMd = 14.dp
    val radiusLg = 24.dp
    val radiusFull = 999.dp
}

object NotesSize {
    val touchMin = 44.dp
    val iconButton = 48.dp
    val rowSignal = 52.dp
    val rowPaper = 60.dp
    val rowIndex = 46.dp
    val fabSignal = 64.dp
    val barHeight = 56.dp
    val drawerWidth = 304.dp
}

/** Non-color roles that change with the theme family (see README › Themes). */
@Immutable
data class NotesShape(val control: androidx.compose.ui.unit.Dp, val media: androidx.compose.ui.unit.Dp, val newNote: androidx.compose.ui.unit.Dp, val rowMin: androidx.compose.ui.unit.Dp, val gutter: androidx.compose.ui.unit.Dp, val labelsUppercase: Boolean)

fun notesShape(family: ThemeFamily) = when (family) {
    ThemeFamily.Signal -> NotesShape(NotesRadius.radiusNone, NotesRadius.radiusNone, NotesRadius.radiusFull, NotesSize.rowSignal, NotesSpacing.space5, true)
    ThemeFamily.Paper -> NotesShape(NotesRadius.radiusLg, NotesRadius.radiusMd, NotesRadius.radiusFull, NotesSize.rowPaper, NotesSpacing.space6, false)
    ThemeFamily.Index -> NotesShape(NotesRadius.radiusSm, NotesRadius.radiusSm, NotesRadius.radiusSm, NotesSize.rowIndex, NotesSpacing.space4, false)
}

val LocalNotesColors = staticCompositionLocalOf { signalDarkColors }
val LocalNotesShape = staticCompositionLocalOf { notesShape(ThemeFamily.Signal) }

