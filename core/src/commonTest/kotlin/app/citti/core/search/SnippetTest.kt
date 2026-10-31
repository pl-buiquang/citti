package app.citti.core.search

import kotlin.test.Test
import kotlin.test.assertEquals

class SnippetTest {
    @Test
    fun newlinesAndSpacesBecomeOneSpace() = assertEquals("a b c", tidySnippet("a\n\n b \t c"))

    @Test
    fun aHitNearTheStartIsKept() = assertEquals("…the \u0002hit\u0003 here", tidySnippet("…the \u0002hit\u0003\nhere"))

    @Test
    fun aFarHitIsBroughtToTheStart() {
        val raw = "…the limit.\n- `accentAndCaseInsensitive`: \u0002ete\u0003 and \u0002ÉTÉ\u0003"
        assertEquals("…itive`: \u0002ete\u0003 and \u0002ÉTÉ\u0003", tidySnippet(raw))
    }

    @Test
    fun aFarHitInALongWordIsCutInTheWord() =
        assertEquals(
            "…" + "x".repeat(8) + "\u0002a\u0003",
            tidySnippet("x".repeat(40) + "\u0002a\u0003"),
        )

    @Test
    fun noHitKeepsTheText() = assertEquals("plain text", tidySnippet("plain\ntext"))
}
