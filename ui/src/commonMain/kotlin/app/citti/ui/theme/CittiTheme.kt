package app.citti.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle

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

/** Entry points for components: `CittiTheme.colors`, `.typography`, `.shape`. */
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
}

@Composable
fun CittiTheme(
    family: ThemeFamily = ThemeFamily.Signal,
    mode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val dark =
        when (mode) {
            ThemeMode.System -> isSystemInDarkTheme()
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
        }
    val fonts = rememberFontResolver()
    val typography = remember(family, fonts) { cittiTypography(family, fonts) }
    CompositionLocalProvider(
        LocalNotesColors provides notesColors(family, dark),
        LocalNotesShape provides notesShape(family),
        LocalCittiTypography provides typography,
        content = content,
    )
}
