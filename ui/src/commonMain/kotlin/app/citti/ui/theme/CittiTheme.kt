package app.citti.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import app.citti.core.appearance.AccentSource
import app.citti.core.appearance.Appearance
import app.citti.core.appearance.ModeChoice
import app.citti.core.appearance.ThemeChoice

/** The text roles components use, mapped from each family's own styles in `NotesType.kt`. */
@Immutable
data class CittiTypography(
    val title: TextStyle,
    val rowTitle: TextStyle,
    val body: TextStyle,
    val ui: TextStyle,
    val meta: TextStyle,
    val label: TextStyle,
    val editor: TextStyle,
)

fun cittiTypography(
    family: ThemeFamily,
    fonts: FontResolver,
): CittiTypography =
    when (family) {
        ThemeFamily.Signal -> {
            SignalType(fonts).let {
                CittiTypography(it.display, it.rowTitle, it.body, it.ui, it.meta, it.label, it.syntax)
            }
        }

        ThemeFamily.Paper -> {
            PaperType(fonts).let {
                CittiTypography(it.display, it.rowTitle, it.body, it.ui, it.meta, it.label, it.body)
            }
        }

        ThemeFamily.Index -> {
            IndexType(fonts).let {
                CittiTypography(it.title, it.rowTitle, it.body, it.ui, it.meta, it.meta, it.editor)
            }
        }
    }

val LocalCittiTypography =
    staticCompositionLocalOf<CittiTypography> {
        error("CittiTheme is missing")
    }

/** The density before the Text size, set by the outermost [CittiTheme]. */
private val LocalUnscaledDensity = staticCompositionLocalOf<Density?> { null }

/** The theme family, for the components whose layout differs per family (the New note button). */
val LocalThemeFamily = staticCompositionLocalOf { ThemeFamily.Signal }

/** Entry points for components: `CittiTheme.colors`, `.typography`, `.shape`, `.family`. */
object CittiTheme {
    val colors: NotesColors
        @Composable @ReadOnlyComposable
        get() = LocalNotesColors.current
    val typography: CittiTypography
        @Composable @ReadOnlyComposable
        get() = LocalCittiTypography.current
    val shape: NotesShape
        @Composable @ReadOnlyComposable
        get() = LocalNotesShape.current
    val family: ThemeFamily
        @Composable @ReadOnlyComposable
        get() = LocalThemeFamily.current
}

/** Themes [content] from the [appearance]: preset or Custom theme, Mode, Accent source and Text size. */
@Composable
fun CittiTheme(
    appearance: Appearance = Appearance(),
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val fonts = rememberFontResolver()
    val dark = appearance.isDark(systemDark)
    val dynamic = if (appearance.accent == AccentSource.DYNAMIC) rememberDynamicAccent(dark) else null
    val theme = remember(appearance, systemDark, dynamic, fonts) { resolveTheme(appearance, systemDark, dynamic, fonts) }
    // The Text size applies to the phone's own density, also inside a nested theme (the editor's preview card).
    val density = LocalUnscaledDensity.current ?: LocalDensity.current
    val scaled = remember(density, theme.textScale) { Density(density.density, density.fontScale * theme.textScale) }
    CompositionLocalProvider(
        LocalNotesColors provides theme.colors,
        LocalNotesShape provides theme.shape,
        LocalCittiTypography provides theme.typography,
        LocalThemeFamily provides theme.family,
        LocalDensity provides scaled,
        LocalUnscaledDensity provides density,
        content = content,
    )
}

/** A preset in a Mode, for the theme sample and tests. */
@Composable
fun CittiTheme(
    family: ThemeFamily,
    mode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val appearance =
        remember(family, mode) {
            Appearance(
                theme =
                    when (family) {
                        ThemeFamily.Signal -> ThemeChoice.SIGNAL
                        ThemeFamily.Paper -> ThemeChoice.PAPER
                        ThemeFamily.Index -> ThemeChoice.INDEX
                    },
                mode =
                    when (mode) {
                        ThemeMode.System -> ModeChoice.SYSTEM
                        ThemeMode.Light -> ModeChoice.LIGHT
                        ThemeMode.Dark -> ModeChoice.DARK
                    },
            )
        }
    CittiTheme(appearance, content)
}
