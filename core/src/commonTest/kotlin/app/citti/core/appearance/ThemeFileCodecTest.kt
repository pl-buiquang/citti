package app.citti.core.appearance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThemeFileCodecTest {
    private fun ok(text: String): CustomTheme = assertIs<DecodeResult.Ok>(ThemeFileCodec.decode(text)).theme

    private fun file(
        base: String = "\"paper\"",
        overrides: String = "{}",
    ) = """{"schema":1,"base":$base,"overrides":$overrides}"""

    @Test
    fun roundTripWithEveryKindOfOverride() {
        val theme =
            CustomTheme(
                base = BaseTheme.INDEX,
                light = mapOf(ColorToken.BG to 0xFFF5F1E8L, ColorToken.INK_MUTED to 0xFF6B665CL, ColorToken.SCRIM to 0x66000000L),
                dark = mapOf(ColorToken.ACCENT to 0xFFFFB224L),
                roles =
                    RoleOverrides(
                        fonts =
                            mapOf(
                                FontRole.UI to FontFace.IBM_PLEX_SANS,
                                FontRole.READING to FontFace.NEWSREADER,
                                FontRole.CODE to FontFace.SYSTEM,
                            ),
                        textScale = 1.1,
                        density = Density.COMPACT,
                        corners = Corners.SOFT,
                        rowMin = 60,
                        gutter = 20,
                        radiusControl = 4,
                        radiusMedia = 8,
                        radiusNew = 999,
                        labelCapitals = false,
                        labelTracking = 0.14,
                    ),
            )
        val text = ThemeFileCodec.encode(theme)
        assertEquals(theme, ok(text))
        assertEquals(text, ThemeFileCodec.encode(ok(text)))
        assertTrue(text.indexOf("\"bg\"") < text.indexOf("\"ink-muted\""))
        assertTrue(text.contains("\"#00000066\""))
    }

    @Test
    fun appDescriptionExampleDecodes() {
        val t =
            ok(
                """{"schema": 1, "base": "paper",
 "overrides": {
   "light": {"bg": "#f5f1e8", "ink-muted": "#6b665c"},
   "dark":  {"accent": "#ffb224"},
   "roles": {"font-ui": "ibm-plex-sans", "font-reading": "newsreader", "text-scale": 1.1,
             "density": "compact", "corners": "soft", "label-case": "sentence"}}}""",
            )
        assertEquals(BaseTheme.PAPER, t.base)
        assertEquals(0xFFF5F1E8L, t.light[ColorToken.BG])
        assertEquals(0xFFFFB224L, t.dark[ColorToken.ACCENT])
        assertEquals(FontFace.NEWSREADER, t.roles.fonts[FontRole.READING])
        assertEquals(1.1, t.roles.textScale)
        assertEquals(Density.COMPACT, t.roles.density)
        assertEquals(Corners.SOFT, t.roles.corners)
        assertEquals(false, t.roles.labelCapitals)
    }

    @Test
    fun unknownKeysAreIgnored() {
        val t = ok("""{"schema":1,"extra":1,"base":"index","overrides":{"light":{"nope":"#000000","bg":"#101010"},"roles":{"zzz":1}}}""")
        assertEquals(mapOf(ColorToken.BG to 0xFF101010L), t.light)
        assertEquals(RoleOverrides(), t.roles)
    }

    @Test
    fun missingOrUnknownBaseIsSignal() {
        assertEquals(BaseTheme.SIGNAL, ok("""{"schema":1,"overrides":{}}""").base)
        assertEquals(BaseTheme.SIGNAL, ok(file(base = "\"neon\"")).base)
        assertEquals(BaseTheme.SIGNAL, ok(file(base = "3")).base)
    }

    @Test
    fun badHexIsDropped() {
        val bad =
            """{"light":{"bg":"red","ink":"#12345","accent":"#GGGGGG","line":"#aabbcc",""" +
                """"focus":"#aabbccd","scrim":"#00000080","surface":5}}"""
        val t = ok(file(overrides = bad))
        assertEquals(mapOf(ColorToken.LINE to 0xFFAABBCCL, ColorToken.SCRIM to 0x80000000L), t.light)
    }

    @Test
    fun unknownFaceDensityCornersAreDropped() {
        val roles = """{"roles":{"font-ui":"comic","font-code":"space-mono","density":"tiny","corners":"blob"}}"""
        val t = ok(file(overrides = roles))
        assertEquals(mapOf(FontRole.CODE to FontFace.SPACE_MONO), t.roles.fonts)
        assertNull(t.roles.density)
        assertNull(t.roles.corners)
    }

    @Test
    fun textScaleIsClamped() {
        assertEquals(1.3, ok(file(overrides = """{"roles":{"text-scale":3}}""")).roles.textScale)
        assertEquals(0.85, ok(file(overrides = """{"roles":{"text-scale":0.1}}""")).roles.textScale)
        assertNull(ok(file(overrides = """{"roles":{"text-scale":"big"}}""")).roles.textScale)
    }

    @Test
    fun outOfRangeNumbersAreDropped() {
        val numbers =
            """{"roles":{"row-min":10,"gutter":99,"radius-control":-1,""" +
                """"radius-media":1000,"radius-new":12,"label-tracking":0.5}}"""
        val r = ok(file(overrides = numbers)).roles
        assertNull(r.rowMin)
        assertNull(r.gutter)
        assertNull(r.radiusControl)
        assertNull(r.radiusMedia)
        assertEquals(12, r.radiusNew)
        assertNull(r.labelTracking)
    }

    @Test
    fun directValuesAndPresetsTogether() {
        val r = ok(file(overrides = """{"roles":{"density":"compact","row-min":70,"corners":"round","radius-control":2}}""")).roles
        assertEquals(Density.COMPACT, r.density)
        assertEquals(70, r.rowMin)
        assertEquals(Corners.ROUND, r.corners)
        assertEquals(2, r.radiusControl)
    }

    @Test
    fun notAThemeFile() {
        assertIs<DecodeResult.NotAThemeFile>(ThemeFileCodec.decode("hello"))
        assertIs<DecodeResult.NotAThemeFile>(ThemeFileCodec.decode("[1]"))
        assertIs<DecodeResult.NotAThemeFile>(ThemeFileCodec.decode("""{"base":"paper","overrides":{}}"""))
        assertIs<DecodeResult.NotAThemeFile>(ThemeFileCodec.decode("""{"schema":2,"overrides":{}}"""))
        assertIs<DecodeResult.NotAThemeFile>(ThemeFileCodec.decode("""{"schema":1,"overrides":[]}"""))
        assertIs<DecodeResult.NotAThemeFile>(ThemeFileCodec.decode("""{"schema":1}"""))
        assertIs<DecodeResult.NotAThemeFile>(ThemeFileCodec.decode(" ".repeat(MAX_THEME_FILE_BYTES + 1)))
    }

    @Test
    fun capIsMeasuredInUtf8Bytes() {
        // 3-byte chars: 30 000 chars is under the cap in chars but 90 000 bytes. A reader truncated at cap + 1 bytes
        // hands over a text shorter than the cap in chars; it must still be reported as too large.
        val bytes = "\u20AC".repeat(MAX_THEME_FILE_BYTES).encodeToByteArray().copyOf(MAX_THEME_FILE_BYTES + 1)
        val multibyte = bytes.decodeToString()
        assertTrue(multibyte.length < MAX_THEME_FILE_BYTES)
        val result = assertIs<DecodeResult.NotAThemeFile>(ThemeFileCodec.decode(multibyte))
        assertEquals("file too large", result.message)
        assertIs<DecodeResult.NotAThemeFile>(ThemeFileCodec.decode(" ".repeat(MAX_THEME_FILE_BYTES + 1)))
    }

    @Test
    fun emptyOverridesIsValid() {
        assertEquals(CustomTheme(BaseTheme.PAPER), ok(file()))
        assertEquals(0, ok(file()).overrideCount)
    }
}
