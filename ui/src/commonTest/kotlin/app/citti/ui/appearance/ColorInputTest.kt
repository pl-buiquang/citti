package app.citti.ui.appearance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ColorInputTest {
    @Test
    fun sixDigitsKeepTheAlphaOfTheCurrentValue() {
        assertEquals(0x59112233L, parseColorInput("#112233", allowsAlpha = true, current = 0x59FFFFFF))
        assertEquals(0xFF112233L, parseColorInput("#112233", allowsAlpha = false, current = 0xFFFFFFFF))
    }

    @Test
    fun eightDigitsNeedAnAlphaToken() {
        assertEquals(0x80112233L, parseColorInput("#11223380", allowsAlpha = true, current = 0xFFFFFFFF))
        assertNull(parseColorInput("#11223380", allowsAlpha = false, current = 0xFFFFFFFF))
    }

    @Test
    fun invalidTextIsRejected() {
        assertNull(parseColorInput("#12", allowsAlpha = true, current = 0xFFFFFFFF))
        assertEquals(true, hasAlpha(0x59FFFFFF))
        assertEquals(false, hasAlpha(0xFFFFFFFF))
    }
}
