package app.citti.core.note

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExcerptTest {
    private fun excerpt(
        content: String?,
        title: String = "Title",
    ) = excerptOf(content, title)

    @Test
    fun noFrontmatter() = assertEquals("First line", excerpt("First line\nsecond"))

    @Test
    fun frontmatterIsSkipped() {
        assertEquals("Body", excerpt("---\ntags: [a]\ndate: 2026-10-31\n---\nBody"))
        assertEquals("Body", excerpt("---\ntags: [a]\n...\n\nBody"))
    }

    @Test
    fun unclosedFrontmatterIsText() = assertEquals("---", excerpt("---\ntags: [a]\nBody"))

    @Test
    fun leadingBomIsIgnored() = assertEquals("Body", excerpt("\uFEFF---\na: 1\n---\nBody"))

    @Test
    fun blankAndCommentLinesAreSkipped() = assertEquals("Body", excerpt("\n  \n<!-- note: draft -->\n\nBody"))

    @Test
    fun headingEqualToTitleIsSkippedIgnoringCase() {
        assertEquals("Body", excerpt("# title\n\nBody"))
        assertEquals("Body", excerpt("## Title ##\nBody"))
    }

    @Test
    fun setextHeadingEqualToTitleIsSkipped() {
        assertEquals("ssl", excerpt("Title\n=====\n\n- ssl"))
        assertEquals("Body", excerpt("title\n---\nBody"))
    }

    @Test
    fun setextHeadingWithAnotherTextIsKept() = assertEquals("Groceries", excerpt("Groceries\n=====\nBody"))

    @Test
    fun differentHeadingIsKeptWithoutHashes() = assertEquals("Groceries", excerpt("# Groceries ##\nBody"))

    @Test
    fun listAndQuoteMarkersAreRemoved() {
        assertEquals("Buy milk", excerpt("- [ ] Buy milk"))
        assertEquals("Done", excerpt("* [x] Done"))
        assertEquals("Step one", excerpt("1. Step one"))
        assertEquals("Step two", excerpt("2) Step two"))
        assertEquals("Quoted", excerpt("> > Quoted"))
    }

    @Test
    fun imageOnlyLineIsSkipped() {
        assertEquals("Caption", excerpt("![[photo.jpg]]\nCaption"))
        assertEquals("Caption", excerpt("![alt](img/photo.png)\n\nCaption"))
    }

    @Test
    fun wikilinks() {
        assertEquals("See the plan", excerpt("See [[projects/citti/plan|the plan]]"))
        assertEquals("See plan", excerpt("See [[projects/citti/plan]]"))
    }

    @Test
    fun markdownLinkKeepsItsText() = assertEquals("Read the docs now", excerpt("Read [the docs](https://x.org/a) now"))

    @Test
    fun emphasisAndCodeMarkersAreRemoved() = assertEquals("bold strike mark code", excerpt("**bold** ~~strike~~ ==mark== `code`"))

    @Test
    fun singleUnderscoresAndStarsAreKept() = assertEquals("snake_case_word and 2*3", excerpt("snake_case_word  and 2*3"))

    @Test
    fun truncatedTo200Chars() = assertEquals("a".repeat(200), excerpt("a".repeat(300)))

    @Test
    fun truncationKeepsCodePointsWhole() = assertEquals("a".repeat(199), excerpt("a".repeat(199) + "😀".repeat(5)))

    @Test
    fun blankNoteIsEmptyNotNull() {
        assertEquals("", excerpt(""))
        assertEquals("", excerpt("# Title\n\n![[a.png]]\n"))
    }

    @Test
    fun givesUpAfter20Lines() = assertEquals("", excerpt("![[a.png]]\n".repeat(20) + "Too far"))

    @Test
    fun nullContentIsNull() = assertNull(excerpt(null))
}
