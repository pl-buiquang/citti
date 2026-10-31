package app.citti.widget

import androidx.compose.ui.text.font.FontFamily
import androidx.glance.color.ColorProvider
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle
import app.citti.ui.theme.SignalType
import app.citti.ui.theme.signalDarkColors
import app.citti.ui.theme.signalLightColors
import androidx.glance.text.FontFamily as GlanceFontFamily

/** The Signal colors as Glance providers: light by day, dark by night, so the widgets follow the system mode (M8 adds the Appearance). */
object WidgetColors {
    val bg = ColorProvider(day = signalLightColors.bg, night = signalDarkColors.bg)
    val surface = ColorProvider(day = signalLightColors.surface, night = signalDarkColors.surface)
    val line = ColorProvider(day = signalLightColors.line, night = signalDarkColors.line)
    val ink = ColorProvider(day = signalLightColors.ink, night = signalDarkColors.ink)
    val inkMuted = ColorProvider(day = signalLightColors.inkMuted, night = signalDarkColors.inkMuted)
    val accent = ColorProvider(day = signalLightColors.accent, night = signalDarkColors.accent)
    val favorite = ColorProvider(day = signalLightColors.favorite, night = signalDarkColors.favorite)
}

/**
 * Text styles from the Signal type scale (sizes from [SignalType]; Glance can't load the Compose font resources, so the
 * families are the system sans, and the system mono for the Note body, the `font-code` role).
 */
object WidgetText {
    private val signal = SignalType { FontFamily.Default }

    val title =
        TextStyle(color = WidgetColors.ink, fontSize = signal.rowTitle.fontSize, fontWeight = FontWeight.Medium)
    val folder = TextStyle(color = WidgetColors.inkMuted, fontSize = signal.meta.fontSize)
    val header = TextStyle(color = WidgetColors.ink, fontSize = signal.ui.fontSize, fontWeight = FontWeight.Bold)
    val star = TextStyle(color = WidgetColors.favorite, fontSize = signal.ui.fontSize, fontWeight = FontWeight.Bold)
    val state = TextStyle(color = WidgetColors.inkMuted, fontSize = signal.ui.fontSize)
    val body = TextStyle(color = WidgetColors.ink, fontSize = signal.syntax.fontSize, fontFamily = GlanceFontFamily.Monospace)
    val more = TextStyle(color = WidgetColors.accent, fontSize = signal.ui.fontSize, fontWeight = FontWeight.Medium)
}
