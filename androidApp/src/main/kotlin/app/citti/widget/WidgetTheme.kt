package app.citti.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.glance.color.ColorProvider
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle
import app.citti.core.appearance.AccentSource
import app.citti.core.appearance.Appearance
import app.citti.core.appearance.ModeChoice
import app.citti.ui.theme.FontResolver
import app.citti.ui.theme.ResolvedTheme
import app.citti.ui.theme.dynamicAccentOf
import app.citti.ui.theme.resolveTheme
import androidx.glance.text.FontFamily as GlanceFontFamily
import androidx.glance.unit.ColorProvider as FixedColorProvider

/**
 * The Text styles of the widgets, from the resolved typography (sizes) multiplied by the Text size. Glance cannot load
 * the bundled fonts, so a face is drawn as the system serif, sans or mono (see [glanceFamily]).
 */
class WidgetTextStyles internal constructor(
    palette: WidgetPalette,
    type: app.citti.ui.theme.CittiTypography,
    private val scale: Float,
) {
    private fun size(style: androidx.compose.ui.text.TextStyle) = (style.fontSize.value * scale).sp

    val title =
        TextStyle(
            color = palette.ink,
            fontSize = size(type.rowTitle),
            fontWeight = FontWeight.Medium,
            fontFamily = glanceFamily(type.rowTitle.fontFamily),
        )
    val folder = TextStyle(color = palette.inkMuted, fontSize = size(type.meta), fontFamily = glanceFamily(type.meta.fontFamily))
    val header =
        TextStyle(
            color = palette.ink,
            fontSize = size(type.ui),
            fontWeight = FontWeight.Bold,
            fontFamily = glanceFamily(type.ui.fontFamily),
        )
    val star = TextStyle(color = palette.favorite, fontSize = size(type.ui), fontWeight = FontWeight.Bold)
    val state = TextStyle(color = palette.inkMuted, fontSize = size(type.ui), fontFamily = glanceFamily(type.ui.fontFamily))
    val body = TextStyle(color = palette.ink, fontSize = size(type.editor), fontFamily = glanceFamily(type.editor.fontFamily))
    val more =
        TextStyle(
            color = palette.accent,
            fontSize = size(type.ui),
            fontWeight = FontWeight.Medium,
            fontFamily = glanceFamily(type.ui.fontFamily),
        )
}

/** serif and mono faces map to their Glance family, every other face (and the system font) to the sans. */
internal fun glanceFamily(family: FontFamily?): GlanceFontFamily =
    when (family) {
        FontFamily.Serif -> GlanceFontFamily.Serif
        FontFamily.Monospace -> GlanceFontFamily.Monospace
        else -> GlanceFontFamily.SansSerif
    }

/** Resolves a font key to the generic family the widget will draw, never loading a font. */
private val genericFonts =
    FontResolver { key ->
        when (key) {
            "paper-serif" -> FontFamily.Serif
            "signal-mono", "index-mono" -> FontFamily.Monospace
            else -> FontFamily.SansSerif
        }
    }

/**
 * The colors and text of the widgets for one [Appearance]. Mode System gives `day`/`night` providers so the launcher
 * decides; Light and Dark give a fixed color. Corners and Density do not apply to widgets.
 */
class WidgetPalette(
    private val light: ResolvedTheme,
    private val dark: ResolvedTheme,
    private val system: Boolean,
    /** True only when the Custom theme turns Capital labels on: a preset's own label style does not change the headers. */
    val labelsUppercase: Boolean = false,
) {
    private fun provider(pick: (app.citti.ui.theme.NotesColors) -> Color): androidx.glance.unit.ColorProvider =
        if (system) ColorProvider(day = pick(light.colors), night = pick(dark.colors)) else FixedColorProvider(pick(light.colors))

    val bg = provider { it.bg }
    val surface = provider { it.surface }
    val line = provider { it.line }
    val ink = provider { it.ink }
    val inkMuted = provider { it.inkMuted }
    val accent = provider { it.accent }
    val favorite = provider { it.favorite }

    val text = WidgetTextStyles(this, light.typography, light.textScale)

    fun label(text: String): String = if (labelsUppercase) text.uppercase() else text

    companion object {
        /** [Appearance] resolved for both variants (the fixed Mode ones use the single variant they need). */
        fun of(
            appearance: Appearance,
            context: Context?,
        ): WidgetPalette {
            fun resolve(systemDark: Boolean): ResolvedTheme {
                val dark = appearance.mode == ModeChoice.DARK || (appearance.mode == ModeChoice.SYSTEM && systemDark)
                val dynamic = if (appearance.accent == AccentSource.DYNAMIC && context != null) dynamicAccentOf(context, dark) else null
                return resolveTheme(appearance, systemDark, dynamic, genericFonts)
            }
            val system = appearance.mode == ModeChoice.SYSTEM
            val custom = appearance.custom?.takeIf { appearance.theme == app.citti.core.appearance.ThemeChoice.CUSTOM }
            return WidgetPalette(
                light = resolve(false),
                dark = if (system) resolve(true) else resolve(false),
                system = system,
                labelsUppercase =
                    custom?.roles?.labelCapitals == true,
            )
        }
    }
}

/** What [LocalWidgetPalette] holds outside a provider (unit tests of a content composable): Signal, following the system. */
val LocalWidgetPalette = compositionLocalOf { WidgetPalette.of(Appearance(), null) }

/** Reads the current Appearance (one indexed query) for a `provideGlance`, so a redraw always uses the latest. */
internal fun Context.widgetPalette(): WidgetPalette = WidgetPalette.of(container().appearanceSettings.current(), this)

/**
 * Runs [content] with the palette of the Appearance, and keeps it current: Glance keeps a widget's composition alive
 * for a while and an `update` recomposes it without running `provideGlance` again, so the palette is observed here
 * instead of being read once in `provideGlance`.
 */
@Composable
internal fun WidgetThemeHost(
    context: Context,
    content: @Composable () -> Unit,
) {
    val settings = context.container().appearanceSettings
    val appearance by remember(settings) { settings.flow() }.collectAsState(initial = remember { settings.current() })
    val palette = remember(appearance) { WidgetPalette.of(appearance, context) }
    CompositionLocalProvider(LocalWidgetPalette provides palette, content = content)
}

object WidgetColors {
    val bg: androidx.glance.unit.ColorProvider @Composable get() = LocalWidgetPalette.current.bg
    val surface: androidx.glance.unit.ColorProvider @Composable get() = LocalWidgetPalette.current.surface
    val line: androidx.glance.unit.ColorProvider @Composable get() = LocalWidgetPalette.current.line
    val ink: androidx.glance.unit.ColorProvider @Composable get() = LocalWidgetPalette.current.ink
    val inkMuted: androidx.glance.unit.ColorProvider @Composable get() = LocalWidgetPalette.current.inkMuted
    val accent: androidx.glance.unit.ColorProvider @Composable get() = LocalWidgetPalette.current.accent
    val favorite: androidx.glance.unit.ColorProvider @Composable get() = LocalWidgetPalette.current.favorite
}

object WidgetText {
    val title: TextStyle @Composable get() = LocalWidgetPalette.current.text.title
    val folder: TextStyle @Composable get() = LocalWidgetPalette.current.text.folder
    val header: TextStyle @Composable get() = LocalWidgetPalette.current.text.header
    val star: TextStyle @Composable get() = LocalWidgetPalette.current.text.star
    val state: TextStyle @Composable get() = LocalWidgetPalette.current.text.state
    val body: TextStyle @Composable get() = LocalWidgetPalette.current.text.body
    val more: TextStyle @Composable get() = LocalWidgetPalette.current.text.more

    @Composable
    fun label(text: String): String = LocalWidgetPalette.current.label(text)
}
