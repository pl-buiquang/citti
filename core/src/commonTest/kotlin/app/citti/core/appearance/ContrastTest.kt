package app.citti.core.appearance

import kotlin.test.Test
import kotlin.test.assertEquals

class ContrastTest {
    private val black = 0xFF000000L
    private val white = 0xFFFFFFFFL

    @Test
    fun blackOnWhiteIs21() = assertEquals(21.0, contrastRatio(black, white), 0.001)

    @Test
    fun equalColorsAre1() = assertEquals(1.0, contrastRatio(0xFF6B665CL, 0xFF6B665CL), 0.0001)

    @Test
    fun knownGreyPair() {
        // #777777 on white is the classic 4.48:1 (just under AA).
        assertEquals(4.48, contrastRatio(0xFF777777L, white), 0.01)
        // #767676 on white is 4.54:1.
        assertEquals(4.54, contrastRatio(0xFF767676L, white), 0.01)
    }

    @Test
    fun symmetric() = assertEquals(contrastRatio(0xFF123456L, 0xFFABCDEFL), contrastRatio(0xFFABCDEFL, 0xFF123456L), 0.0)

    @Test
    fun compositeOverBlendsByAlpha() {
        assertEquals(0xFF7F7F7FL, compositeOver(0x80000000L, 0xFFFFFFFFL))
        assertEquals(0xFF123456L, compositeOver(0xFF123456L, 0xFFFFFFFFL))
        assertEquals(0xFFFFFFFFL, compositeOver(0x00000000L, 0xFFFFFFFFL))
    }
}
