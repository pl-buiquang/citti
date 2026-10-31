package app.citti.ui.appearance

import kotlin.test.Test
import kotlin.test.assertEquals

class HsvTest {
    @Test
    fun primariesAndGrey() {
        assertEquals(Hsv(0f, 1f, 1f), argbToHsv(0xFFFF0000))
        assertEquals(120f, argbToHsv(0xFF00FF00).h)
        assertEquals(240f, argbToHsv(0xFF0000FF).h)
        assertEquals(Hsv(0f, 0f, 0f), argbToHsv(0xFF000000))
        assertEquals(0.5f, argbToHsv(0xFF808080).v, 0.01f)
    }

    @Test
    fun roundTripIsTheIdentity() {
        for (c in listOf(0xFFFFB224, 0xFF121211, 0xFFF4F4EF, 0xFF6B665C, 0x66000000, 0xFF0000FF, 0xFFFFFFFF, 0x80FF00AA)) {
            assertEquals(c, hsvToArgb(argbToHsv(c)), "color ${c.toString(16)}")
        }
    }
}
