package app.citti.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.citti.core.appearance.AccentSource
import app.citti.core.appearance.Appearance
import app.citti.core.appearance.BaseTheme
import app.citti.core.appearance.ColorToken
import app.citti.core.appearance.Corners
import app.citti.core.appearance.CustomTheme
import app.citti.core.appearance.Density
import app.citti.core.appearance.FontFace
import app.citti.core.appearance.FontRole
import app.citti.core.appearance.ModeChoice
import app.citti.core.appearance.RoleOverrides
import app.citti.core.appearance.ThemeChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ResolveThemeTest {
    private val fonts =
        FontResolver { key ->
            when (key) {
                "signal" -> FontFamily.SansSerif
                "signal-mono", "index-mono" -> FontFamily.Monospace
                "paper-serif" -> FontFamily.Serif
                "paper-sans" -> FontFamily.Cursive
                else -> FontFamily.Default
            }
        }
    private val dynamic = DynamicAccent(0xFF112233, 0xFF445566, 0xFF778899, 0xFFAABBCC)

    private fun resolve(
        a: Appearance,
        systemDark: Boolean = false,
        dyn: DynamicAccent? = null,
    ) = resolveTheme(a, systemDark, dyn, fonts)

    private fun custom(
        base: BaseTheme = BaseTheme.PAPER,
        light: Map<ColorToken, Long> = emptyMap(),
        dark: Map<ColorToken, Long> = emptyMap(),
        roles: RoleOverrides = RoleOverrides(),
    ) = Appearance(ThemeChoice.CUSTOM, custom = CustomTheme(base, light, dark, roles))

    @Test
    fun presetsMatchTheGeneratedTokens() {
        val presets =
            listOf(
                ThemeChoice.SIGNAL to ThemeFamily.Signal,
                ThemeChoice.PAPER to ThemeFamily.Paper,
                ThemeChoice.INDEX to ThemeFamily.Index,
            )
        for ((choice, family) in presets) {
            for (dark in listOf(false, true)) {
                val t = resolve(Appearance(choice, if (dark) ModeChoice.DARK else ModeChoice.LIGHT))
                assertEquals(notesColors(family, dark).copy(isDark = dark), t.colors)
                assertEquals(notesShape(family), t.shape)
                assertEquals(cittiTypography(family, fonts), t.typography)
                assertEquals(family, t.family)
                assertEquals(dark, t.dark)
                assertEquals(1f, t.textScale)
            }
        }
    }

    @Test
    fun modeSystemFollowsTheSystem() {
        assertEquals(true, resolve(Appearance(), systemDark = true).dark)
        assertEquals(false, resolve(Appearance(), systemDark = false).dark)
        assertEquals(false, resolve(Appearance(mode = ModeChoice.LIGHT), systemDark = true).dark)
        assertEquals(true, resolve(Appearance(mode = ModeChoice.DARK), systemDark = false).dark)
    }

    @Test
    fun colorOverrideAppliesOnlyToItsVariant() {
        val a = custom(light = mapOf(ColorToken.BG to 0xFF123456))
        assertEquals(Color(0xFF123456), resolve(a.copy(mode = ModeChoice.LIGHT)).colors.bg)
        assertEquals(notesColors(ThemeFamily.Paper, true).bg, resolve(a.copy(mode = ModeChoice.DARK)).colors.bg)
    }

    @Test
    fun dynamicReplacesExactlyTheFourAccentColorsAndBeatsOverrides() {
        val a =
            custom(
                light = mapOf(ColorToken.ACCENT to 0xFF000001, ColorToken.FOCUS to 0xFF000002),
            ).copy(accent = AccentSource.DYNAMIC, mode = ModeChoice.LIGHT)
        val base = notesColors(ThemeFamily.Paper, false)
        val c = resolve(a, dyn = dynamic).colors
        assertEquals(Color(0xFF112233), c.accent)
        assertEquals(Color(0xFF445566), c.onAccent)
        assertEquals(Color(0xFF778899), c.accentInk)
        assertEquals(Color(0xFFAABBCC), c.accentSoft)
        assertEquals(Color(0xFF000002), c.focus)
        assertEquals(base.favorite, c.favorite)
        // No dynamic palette: the theme's own accent (with the override) stays.
        assertEquals(Color(0xFF000001), resolve(a, dyn = null).colors.accent)
    }

    @Test
    fun densityAndCornersPresetsScaleTheBase() {
        val base = notesShape(ThemeFamily.Paper)
        val s = resolve(custom(roles = RoleOverrides(density = Density.COMPACT, corners = Corners.SQUARE))).shape
        assertEquals(Math.round(base.rowMin.value * 0.85f).dp, s.rowMin)
        assertEquals(Math.round(base.gutter.value * 0.85f).dp, s.gutter)
        assertEquals(0.dp, s.control)
        assertEquals(0.dp, s.media)
        assertEquals(0.dp, s.newNote)
        val round =
            resolve(
                custom(base = BaseTheme.INDEX, roles = RoleOverrides(corners = Corners.ROUND, density = Density.COMFORTABLE)),
            ).shape
        assertEquals(listOf(24.dp, 14.dp, 999.dp), listOf(round.control, round.media, round.newNote))
        assertEquals(Math.round(notesShape(ThemeFamily.Index).rowMin.value * 1.15f).dp, round.rowMin)
        assertEquals(notesShape(ThemeFamily.Paper), resolve(custom(roles = RoleOverrides(density = Density.DEFAULT))).shape)
    }

    @Test
    fun directValueBeatsItsPreset() {
        val s =
            resolve(
                custom(roles = RoleOverrides(density = Density.COMPACT, rowMin = 70, corners = Corners.SQUARE, radiusControl = 3)),
            ).shape
        assertEquals(70.dp, s.rowMin)
        assertEquals(3.dp, s.control)
        assertEquals(0.dp, s.media)
    }

    @Test
    fun capitalLabelsOnPaperGiveUppercaseAndTracking() {
        val t = resolve(custom(roles = RoleOverrides(labelCapitals = true)))
        assertEquals(true, t.shape.labelsUppercase)
        assertEquals(0.14.em, t.typography.label.letterSpacing)
        val off = resolve(custom(base = BaseTheme.SIGNAL, roles = RoleOverrides(labelCapitals = false)))
        assertEquals(false, off.shape.labelsUppercase)
        assertEquals(0.0.em, off.typography.label.letterSpacing)
        assertEquals(0.1.em, resolve(custom(roles = RoleOverrides(labelTracking = 0.1))).typography.label.letterSpacing)
    }

    @Test
    fun eachFontRoleChangesExactlyItsStyles() {
        val base = cittiTypography(ThemeFamily.Paper, fonts)

        fun changed(role: FontRole): Set<String> {
            val t = resolve(custom(roles = RoleOverrides(fonts = mapOf(role to FontFace.SYSTEM)))).typography
            return buildMap {
                put("title", t.title to base.title)
                put("rowTitle", t.rowTitle to base.rowTitle)
                put("body", t.body to base.body)
                put("ui", t.ui to base.ui)
                put("meta", t.meta to base.meta)
                put("label", t.label to base.label)
                put("editor", t.editor to base.editor)
            }.filter { (_, p) ->
                p.first != p.second
            }.onEach { (_, p) ->
                assertEquals(FontFamily.Default, p.first.fontFamily)
                assertEquals(p.second.fontSize, p.first.fontSize)
                assertEquals(p.second.fontWeight, p.first.fontWeight)
                assertEquals(p.second.lineHeight, p.first.lineHeight)
            }.keys
        }
        // Paper's meta already uses a sans family and its editor is the body style, so check what must change.
        assertEquals(setOf("ui", "label"), changed(FontRole.UI))
        assertEquals(setOf("title", "rowTitle"), changed(FontRole.DISPLAY))
        assertEquals(setOf("body"), changed(FontRole.READING))
        assertEquals(setOf("editor"), changed(FontRole.CODE))
        assertEquals(setOf("meta"), changed(FontRole.META))
    }

    @Test
    fun bundledFaceUsesItsResolverKey() {
        val t = resolve(custom(roles = RoleOverrides(fonts = mapOf(FontRole.READING to FontFace.SPACE_MONO)))).typography
        assertEquals(FontFamily.Monospace, t.body.fontFamily)
    }

    @Test
    fun emptyCustomSlotResolvesAsSignal() {
        val t = resolve(Appearance(ThemeChoice.CUSTOM, ModeChoice.LIGHT))
        assertEquals(ThemeFamily.Signal, t.family)
        assertEquals(notesColors(ThemeFamily.Signal, false).copy(isDark = false), t.colors)
    }

    @Test
    fun customIgnoredWhilePresetSelected() {
        val a = custom(light = mapOf(ColorToken.BG to 0xFF123456)).copy(theme = ThemeChoice.INDEX, mode = ModeChoice.LIGHT)
        assertEquals(ThemeFamily.Index, resolve(a).family)
        assertNotEquals(Color(0xFF123456), resolve(a).colors.bg)
    }

    @Test
    fun baseChangeKeepsOverrides() {
        val overrides = mapOf(ColorToken.BG to 0xFF123456)
        for (base in BaseTheme.entries) {
            val t = resolve(custom(base = base, light = overrides).copy(mode = ModeChoice.LIGHT))
            assertEquals(Color(0xFF123456), t.colors.bg)
            assertEquals(base.toFamily(), t.family)
        }
    }

    @Test
    fun textScaleComesFromTheCustomTheme() {
        assertEquals(1.3f, resolve(custom(roles = RoleOverrides(textScale = 1.3))).textScale)
    }

    @Test
    fun dynamicAccentMapping() {
        val stops =
            mapOf(
                AccentStop.S0 to 0xFFFFFFFF,
                AccentStop.S100 to 0xFF100000,
                AccentStop.S200 to 0xFF200000,
                AccentStop.S600 to 0xFF600000,
                AccentStop.S700 to 0xFF700000,
                AccentStop.S800 to 0xFF800000,
            )
        val light = dynamicAccentFrom(false) { stops.getValue(it) }
        assertEquals(DynamicAccent(0xFF600000, 0xFFFFFFFF, 0xFF700000, 0xFF100000), light)
        val dark = dynamicAccentFrom(true) { stops.getValue(it) }
        assertEquals(0xFF200000, dark.accent)
        assertEquals(0xFF800000, dark.onAccent)
        assertEquals(0xFF200000, dark.accentInk)
        assertEquals(0x59800000L, dark.accentSoft)
    }
}
