package app.citti.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.glance.color.DayNightColorProvider
import androidx.glance.unit.FixedColorProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.citti.core.appearance.Appearance
import app.citti.core.appearance.BaseTheme
import app.citti.core.appearance.ColorToken
import app.citti.core.appearance.CustomTheme
import app.citti.core.appearance.ModeChoice
import app.citti.core.appearance.RoleOverrides
import app.citti.core.appearance.ThemeChoice
import app.citti.ui.theme.ThemeFamily
import app.citti.ui.theme.notesColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.glance.text.FontFamily as GlanceFontFamily

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class WidgetPaletteTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun modeSystemLetsTheLauncherDecide() {
        val bg = WidgetPalette.of(Appearance(), context).bg as DayNightColorProvider
        assertEquals(notesColors(ThemeFamily.Signal, dark = false).bg, bg.day)
        assertEquals(notesColors(ThemeFamily.Signal, dark = true).bg, bg.night)
        assertNotEquals(bg.day, bg.night)
    }

    @Test
    fun fixedLightAndDarkIgnoreTheLauncher() {
        val light = WidgetPalette.of(Appearance(theme = ThemeChoice.PAPER, mode = ModeChoice.LIGHT), context).bg as FixedColorProvider
        val dark = WidgetPalette.of(Appearance(theme = ThemeChoice.PAPER, mode = ModeChoice.DARK), context).bg as FixedColorProvider
        assertEquals(notesColors(ThemeFamily.Paper, dark = false).bg, light.color)
        assertEquals(notesColors(ThemeFamily.Paper, dark = true).bg, dark.color)
    }

    @Test
    fun aColorOverrideReachesTheWidget() {
        val custom = CustomTheme(BaseTheme.SIGNAL, light = mapOf(ColorToken.BG to 0xFF102030L))
        val bg =
            WidgetPalette
                .of(
                    Appearance(theme = ThemeChoice.CUSTOM, mode = ModeChoice.LIGHT, custom = custom),
                    context,
                ).bg as FixedColorProvider
        assertEquals(Color(0xFF102030), bg.color)
    }

    @Test
    fun facesMapToTheGenericGlanceFamilies() {
        assertEquals(GlanceFontFamily.Serif, glanceFamily(FontFamily.Serif))
        assertEquals(GlanceFontFamily.Monospace, glanceFamily(FontFamily.Monospace))
        assertEquals(GlanceFontFamily.SansSerif, glanceFamily(FontFamily.Default))
        // Signal: sans titles, mono body. Paper: serif titles.
        val signal = WidgetPalette.of(Appearance(), context).text
        assertEquals(GlanceFontFamily.SansSerif, signal.title.fontFamily)
        assertEquals(GlanceFontFamily.Monospace, signal.body.fontFamily)
        assertEquals(
            GlanceFontFamily.Serif,
            WidgetPalette
                .of(Appearance(theme = ThemeChoice.PAPER), context)
                .text.title.fontFamily,
        )
    }

    @Test
    fun theTextSizeMultipliesTheSizes() {
        val base =
            WidgetPalette
                .of(Appearance(), context)
                .text.title.fontSize!!
        val custom = CustomTheme(BaseTheme.SIGNAL, roles = RoleOverrides(textScale = 1.3))
        val scaled =
            WidgetPalette
                .of(Appearance(theme = ThemeChoice.CUSTOM, custom = custom), context)
                .text.title.fontSize!!
        assertEquals((base.value * 1.3f).sp, scaled)
        assertTrue(scaled.value > base.value)
    }

    @Test
    fun capitalLabelsUppercaseTheHeaders() {
        assertEquals("favorites", WidgetPalette.of(Appearance(), context).label("favorites"))
        val custom = CustomTheme(BaseTheme.PAPER, roles = RoleOverrides(labelCapitals = true))
        val palette = WidgetPalette.of(Appearance(theme = ThemeChoice.CUSTOM, custom = custom), context)
        assertEquals("FAVORITES", palette.label("favorites"))
    }
}
