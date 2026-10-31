package app.citti.core.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchQueryTest {
    private fun match(raw: String) = toFtsMatch(raw)?.match

    @Test
    fun wordsArePrefixesAndAnded() {
        assertEquals(FtsQuery("\"sync*\" \"et*\"", phrases = 2, titleOnly = false), toFtsMatch("sync et"))
        assertEquals("\"sync*\" \"et*\"", match("  sync \t\n et  "))
    }

    @Test
    fun quotedTextIsAPhrase() {
        assertEquals(FtsQuery("\"two words\" \"x*\"", phrases = 2, titleOnly = false), toFtsMatch("\"two  words\" x"))
        assertEquals("\"one\"", match("\"one\""))
    }

    @Test
    fun aQuoteEndsTheWordBeforeIt() {
        assertEquals("\"ab*\" \"cd ef\"", match("ab\"cd ef\""))
    }

    @Test
    fun unbalancedQuoteRunsToTheEnd() {
        assertEquals("\"a*\" \"b c\"", match("a \"b c"))
        assertEquals("\"ab*\"", match("ab \""))
    }

    @Test
    fun operatorsAreQuotedAsText() {
        assertEquals("\"a*\" \"OR*\" \"b*\"", match("a OR b"))
        assertEquals("\"-x*\"", match("-x"))
        assertEquals("\"title:x*\"", match("title:x"))
        assertEquals("\"NEAR*\" \"ab*\"", match("NEAR ab"))
    }

    @Test
    fun punctuationOnlyTokensAreDropped() {
        assertEquals("\"ab*\"", match("ab - ... ** \"!?\""))
        assertNull(toFtsMatch("- ... \"!?\""))
    }

    @Test
    fun singleCharacterSearchesTitlesOnly() {
        assertEquals(FtsQuery("title:a*", phrases = 1, titleOnly = true), toFtsMatch("a"))
        assertEquals(FtsQuery("title:7*", phrases = 1, titleOnly = true), toFtsMatch(" 7 "))
        assertEquals(FtsQuery("title:é*", phrases = 1, titleOnly = true), toFtsMatch("é"))
        // A quoted letter, two words or a letter with punctuation stay quoted, over every column.
        assertEquals(FtsQuery("\"a\"", phrases = 1, titleOnly = false), toFtsMatch("\"a\""))
        assertEquals(FtsQuery("\"a*\" \"b*\"", phrases = 2, titleOnly = false), toFtsMatch("a b"))
        assertEquals(FtsQuery("\"a.*\"", phrases = 1, titleOnly = false), toFtsMatch("a."))
        assertEquals(FtsQuery("\"ab*\"", phrases = 1, titleOnly = false), toFtsMatch("ab"))
    }

    @Test
    fun blankIsNull() {
        assertNull(toFtsMatch(""))
        assertNull(toFtsMatch("   "))
        assertNull(toFtsMatch("\"\""))
    }
}
