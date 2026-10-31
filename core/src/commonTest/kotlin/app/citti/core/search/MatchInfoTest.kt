package app.citti.core.search

import kotlin.test.Test
import kotlin.test.assertEquals

class MatchInfoTest {
    /**
     * A matchinfo 'x' blob: for each phrase, the hits in this row of (title, content, category). The other two ints
     * of each triple (hits in all rows, rows with hits) are filled with noise, which tierOf must ignore.
     */
    private fun blob(vararg phrases: Triple<Int, Int, Int>): ByteArray {
        val ints = phrases.flatMap { (t, c, p) -> listOf(t, 99, 7, c, 99, 7, p, 99, 7) }
        return ByteArray(ints.size * 4) { i -> ((ints[i / 4] ushr (8 * (i % 4))) and 0xFF).toByte() }
    }

    @Test
    fun intsAreLittleEndian() {
        val info = byteArrayOf(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0x02, 0x01, 0, 0)
        assertEquals(1, hitsInRow(info, phrase = 0, column = 0))
        // index 3 * (0 * 3 + 1) = 3, i.e. bytes 12..15: 0x0102
        assertEquals(0x0102, hitsInRow(info, phrase = 0, column = 1))
        assertEquals(0xFFFFFFFFL, hitsInRow(ByteArray(4) { -1 }, phrase = 0, column = 0))
    }

    @Test
    fun onePhrase() {
        assertEquals(1, tierOf(blob(Triple(1, 0, 0)), 1))
        assertEquals(1, tierOf(blob(Triple(2, 3, 1)), 1))
        assertEquals(2, tierOf(blob(Triple(0, 4, 0)), 1))
        assertEquals(3, tierOf(blob(Triple(0, 0, 1)), 1))
    }

    @Test
    fun twoPhrases() {
        assertEquals(1, tierOf(blob(Triple(1, 0, 0), Triple(1, 1, 0)), 2))
        assertEquals(2, tierOf(blob(Triple(1, 0, 0), Triple(0, 1, 0)), 2))
        assertEquals(3, tierOf(blob(Triple(1, 0, 0), Triple(0, 0, 1)), 2))
    }

    @Test
    fun threePhrases() {
        assertEquals(1, tierOf(blob(Triple(1, 0, 0), Triple(1, 0, 0), Triple(3, 0, 0)), 3))
        assertEquals(2, tierOf(blob(Triple(0, 1, 0), Triple(1, 0, 0), Triple(0, 2, 1)), 3))
        assertEquals(3, tierOf(blob(Triple(1, 1, 0), Triple(0, 1, 0), Triple(0, 0, 2)), 3))
    }
}
