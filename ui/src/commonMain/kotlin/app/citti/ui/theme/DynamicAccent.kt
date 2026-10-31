package app.citti.ui.theme

import androidx.compose.runtime.Composable

/** The four accent colors taken from the wallpaper (Accent source: Dynamic), as ARGB values. */
data class DynamicAccent(
    val accent: Long,
    val onAccent: Long,
    val accentInk: Long,
    val accentSoft: Long,
)

/** The wallpaper palette stops used by [dynamicAccentFrom]. */
enum class AccentStop { S0, S100, S200, S600, S700, S800 }

/**
 * Maps the system accent palette to the four accent colors: light `accent1_600` / white / `accent1_700` / `accent1_100`,
 * dark `accent1_200` / `accent1_800` / `accent1_200` / `accent1_800` at low alpha. [stop] returns an opaque ARGB value.
 */
fun dynamicAccentFrom(
    dark: Boolean,
    stop: (AccentStop) -> Long,
): DynamicAccent =
    if (dark) {
        DynamicAccent(
            accent = stop(AccentStop.S200),
            onAccent = stop(AccentStop.S800),
            accentInk = stop(AccentStop.S200),
            accentSoft = (stop(AccentStop.S800) and 0xFFFFFFL) or (0x59L shl 24),
        )
    } else {
        DynamicAccent(
            accent = stop(AccentStop.S600),
            onAccent = 0xFFFFFFFFL,
            accentInk = stop(AccentStop.S700),
            accentSoft = stop(AccentStop.S100),
        )
    }

/** The wallpaper accent for the Android actual; `null` where there is none. */
@Composable
expect fun rememberDynamicAccent(dark: Boolean): DynamicAccent?
