package app.citti.core.search

import kotlin.test.Test
import kotlin.test.assertEquals

class FoldingTest {
    @Test
    fun foldsCaseAndAccents() {
        assertEquals("ete", foldedText("Été"))
        assertEquals("ca va tres bien", foldedText("Ça va très bien"))
        assertEquals("naive", foldedText("naïve"))
    }

    @Test
    fun ligaturesBecomeTwoLettersAndMapBack() {
        val f = fold("Cœur d'été")
        assertEquals("coeur d'ete", f.text)
        // "oe" is the one character œ.
        assertEquals(1..1, f.sourceRange(1, 3))
        assertEquals(0..2, f.sourceRange(0, 4))
        assertEquals(7..9, f.sourceRange(8, 11))
    }

    @Test
    fun theTablesLineUp() {
        assertEquals("ssz", foldedText("ßž"))
        assertEquals(fromLength, toLength)
    }

    private val fromLength = "àáâãäåāăąçćĉċčďđèéêëēĕėęěĝğġģĥħìíîïĩīĭįıĵķĺļľŀłñńņňŉòóôõöøōŏőŕŗřśŝşšţťŧùúûüũūŭůűųŵýÿŷźżž".length
    private val toLength = ("aaaaaaaaacccccddeeeeeeeeegggghhiiiiiiiiijklllllnnnnnooooooooorrrssssttt" + "uuuuuuuuuuwyyyzzz").length
}
