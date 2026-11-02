package app.citti.core.markdown

import kotlin.test.Test
import kotlin.test.assertEquals

class TextOpsTest {
    private fun doc(text: String) = MarkdownDocument(text)

    /** Applies [edit] to [text]. */
    private fun run(
        text: String,
        sel: Selection,
        op: (MarkdownDocument, Selection) -> Edit,
    ): Pair<String, Selection> {
        val e = op(doc(text), sel)
        return text.apply(e) to e.selection
    }

    private fun caret(n: Int) = Selection(n)

    // ---- bold / italic ----

    @Test
    fun boldWrapsTheSelection() {
        val (t, s) = run("a word b", Selection(2, 6), TextOps::bold)
        assertEquals("a **word** b", t)
        assertEquals(Selection(4, 8), s)
    }

    @Test
    fun boldUnwrapsAWrappedSelectionInside() {
        val (t, s) = run("a **word** b", Selection(4, 8), TextOps::bold)
        assertEquals("a word b", t)
        assertEquals(Selection(2, 6), s)
    }

    @Test
    fun boldUnwrapsWhenTheSelectionIncludesTheMarks() {
        val (t, _) = run("a **word** b", Selection(2, 10), TextOps::bold)
        assertEquals("a word b", t)
    }

    @Test
    fun boldAtACaretInsideAWrappedWordUnwraps() {
        val (t, s) = run("a **word** b", caret(6), TextOps::bold)
        assertEquals("a word b", t)
        assertEquals(Selection(2, 6), s)
    }

    @Test
    fun boldWithNoSelectionInsertsFourStars() {
        val (t, s) = run("ab", caret(1), TextOps::bold)
        assertEquals("a****b", t)
        assertEquals(caret(3), s)
    }

    @Test
    fun strikeWrapsTheSelection() {
        val (t, s) = run("a word b", Selection(2, 6), TextOps::strike)
        assertEquals("a ~~word~~ b", t)
        assertEquals(Selection(4, 8), s)
    }

    @Test
    fun strikeUnwrapsFromInsideAndFromOutside() {
        assertEquals("a word b", run("a ~~word~~ b", Selection(4, 8), TextOps::strike).first)
        assertEquals("a word b", run("a ~~word~~ b", Selection(2, 10), TextOps::strike).first)
        assertEquals("a word b", run("a ~~word~~ b", caret(6), TextOps::strike).first)
    }

    @Test
    fun strikeWithNoSelectionInsertsFourTildes() {
        val (t, s) = run("ab", caret(1), TextOps::strike)
        assertEquals("a~~~~b", t)
        assertEquals(caret(3), s)
    }

    @Test
    fun italicUsesUnderscoreAndRecognisesStar() {
        assertEquals("a _x_ b", run("a x b", Selection(2, 3), TextOps::italic).first)
        assertEquals("a x b", run("a _x_ b", Selection(3, 4), TextOps::italic).first)
        assertEquals("a x b", run("a *x* b", Selection(3, 4), TextOps::italic).first)
        // ** is bold, not italic: wrapping a bold word adds a mark.
        assertEquals("_**x**_", run("**x**", Selection(0, 5), TextOps::italic).first)
    }

    // ---- heading ----

    @Test
    fun headingCycles() {
        var text = "title"
        val seen = mutableListOf(text)
        repeat(4) {
            text = run(text, caret(2), TextOps::heading).first
            seen += text
        }
        assertEquals(listOf("title", "# title", "## title", "### title", "title"), seen)
        assertEquals("title", run("#### title", caret(0), TextOps::heading).first)
    }

    @Test
    fun headingOnEachSelectedLine() {
        val (t, s) = run("one\n## two\nthree", Selection(1, 14), TextOps::heading)
        assertEquals("# one\n### two\n# three", t)
        assertEquals(Selection(3, 19), s)
    }

    @Test
    fun aSelectionEndingAtALineStartLeavesThatLineAlone() {
        assertEquals("# one\ntwo", run("one\ntwo", Selection(0, 4), TextOps::heading).first)
    }

    // ---- list / checkbox ----

    @Test
    fun listAddsAndRemovesMarkers() {
        assertEquals("- a\n- b", run("a\nb", Selection(0, 3), TextOps::list).first)
        assertEquals("a\nb", run("- a\n- b", Selection(0, 7), TextOps::list).first)
        // Mixed: the lines without a marker get one.
        assertEquals("- a\n- b", run("- a\nb", Selection(0, 5), TextOps::list).first)
        assertEquals("  - a", run("  a", caret(2), TextOps::list).first)
    }

    @Test
    fun listTurnsATaskIntoAPlainItem() {
        assertEquals("- a", run("- [ ] a", caret(5), TextOps::list).first)
        assertEquals("- a", run("- [x] a", caret(5), TextOps::list).first)
    }

    @Test
    fun checkboxRules() {
        assertEquals("- [ ] a", run("a", caret(0), TextOps::checkbox).first)
        assertEquals("- [ ] a", run("- a", caret(0), TextOps::checkbox).first)
        assertEquals("1. [ ] a", run("1. a", caret(0), TextOps::checkbox).first)
        assertEquals("- [x] a", run("- [ ] a", caret(0), TextOps::checkbox).first)
        assertEquals("- [ ] a", run("- [x] a", caret(0), TextOps::checkbox).first)
        assertEquals("- [ ] a", run("- [X] a", caret(0), TextOps::checkbox).first)
        assertEquals("- [ ] a\n- [ ] b", run("a\nb", Selection(0, 3), TextOps::checkbox).first)
    }

    // ---- link ----

    @Test
    fun linkWithAndWithoutSelection() {
        val (t, s) = run("see x now", Selection(4, 5), TextOps::link)
        assertEquals("see [[|x]] now", t)
        assertEquals(caret(6), s)
        val (t2, s2) = run("see  now", caret(4), TextOps::link)
        assertEquals("see [[]] now", t2)
        assertEquals(caret(6), s2)
    }

    // ---- enter ----

    private fun enter(
        text: String,
        at: Int = text.length,
    ) = run(text, caret(at), TextOps::enter)

    @Test
    fun enterContinuesEachMarker() {
        assertEquals("- a\n- ", enter("- a").first)
        assertEquals("* a\n* ", enter("* a").first)
        assertEquals("+ a\n+ ", enter("+ a").first)
        assertEquals("1. a\n2. ", enter("1. a").first)
        assertEquals("9. a\n10. ", enter("9. a").first)
        assertEquals("3) a\n4) ", enter("3) a").first)
        assertEquals("- [x] a\n- [ ] ", enter("- [x] a").first)
        assertEquals("- [ ] a\n- [ ] ", enter("- [ ] a").first)
        assertEquals(caret(6), enter("- a").second)
    }

    @Test
    fun enterKeepsTheIndent() {
        assertEquals("  - a\n  - ", enter("  - a").first)
    }

    @Test
    fun enterOnAnEmptyItemRemovesItsMarker() {
        val (t, s) = enter("- a\n- ")
        assertEquals("- a\n", t)
        assertEquals(caret(4), s)
        assertEquals("  ", enter("  - ").first)
        assertEquals("- [ ] a\n", enter("- [ ] a\n- [ ] ").first)
    }

    @Test
    fun enterInTheMiddleSplitsTheItem() {
        assertEquals("- ab\n- cd", enter("- abcd", 4).first)
    }

    @Test
    fun enterElsewhereIsPlain() {
        assertEquals("text\n", enter("text").first)
        assertEquals("```\ncode\n- x\n", enter("```\ncode\n- x").first)
        assertEquals("---\n- a\n", enter("---\n- a").first)
        // Before the marker: plain.
        assertEquals("\n- a", enter("- a", 0).first)
    }

    // ---- pressed ----

    @Test
    fun pressedStates() {
        fun pressed(
            text: String,
            at: Int,
        ) = TextOps.pressed(doc(text), caret(at))
        assertEquals(setOf(ToolbarAction.Bold), pressed("a **b** c", 5))
        assertEquals(setOf(ToolbarAction.Italic), pressed("a _b_ c", 3))
        assertEquals(setOf(ToolbarAction.Strike), pressed("a ~~b~~ c", 4))
        assertEquals(setOf(ToolbarAction.Heading), pressed("## t", 4))
        assertEquals(setOf(ToolbarAction.List), pressed("- t", 3))
        assertEquals(setOf(ToolbarAction.List, ToolbarAction.Checkbox), pressed("- [ ] t", 7))
        assertEquals(setOf(ToolbarAction.Link), pressed("[[Note]]", 4))
        assertEquals(emptySet(), pressed("plain", 2))
        assertEquals(emptySet(), pressed("```\n**x**\n```", 7))
    }

    // ---- invariants ----

    private val samples =
        listOf(
            "a word b",
            "a **word** b",
            "# t\n## u\nplain",
            "- a\n- [ ] b\n1. c\n\nd",
            "  - x\n  - [x] y",
            "> quote\n- list",
            "x _y_ and *z*",
            "line one\nline two\nline three",
        )

    @Test
    fun anEditThenItsInverseGivesBackTheInput() {
        val ops: List<(MarkdownDocument, Selection) -> Edit> =
            listOf(TextOps::bold, TextOps::italic, TextOps::heading, TextOps::list, TextOps::checkbox, TextOps::link, TextOps::enter)
        for (text in samples) {
            for (a in text.indices step 3) {
                for (b in listOf(a, minOf(text.length, a + 4))) {
                    for (op in ops) {
                        val e = op(doc(text), Selection(a, b))
                        val after = text.apply(e)
                        val inverse = Edit(e.start, e.start + e.replacement.length, text.substring(e.start, e.end), Selection(a, b))
                        assertEquals(text, after.apply(inverse), "$text [$a,$b]")
                    }
                }
            }
        }
    }

    @Test
    fun operationsNeverChangeBytesOutsideTheirRange() {
        val ops: List<(MarkdownDocument, Selection) -> Edit> =
            listOf(TextOps::bold, TextOps::italic, TextOps::heading, TextOps::list, TextOps::checkbox, TextOps::link, TextOps::enter)
        for (text in samples) {
            for (a in text.indices step 2) {
                val sel = Selection(a, minOf(text.length, a + 3))
                for (op in ops) {
                    val e = op(doc(text), sel)
                    val after = text.apply(e)
                    assertEquals(text.substring(0, e.start), after.substring(0, e.start))
                    assertEquals(text.substring(e.end), after.substring(after.length - (text.length - e.end)))
                }
            }
        }
    }

    // ---- insertBlock ----

    private fun insert(
        text: String,
        at: Int,
    ): Pair<String, Selection> {
        val e = TextOps.insertBlock(text, at, "![](a.jpg)")
        return text.apply(e) to e.selection
    }

    @Test
    fun insertBlockAtALineStartAddsANewlineAfter() {
        assertEquals("one\n![](a.jpg)\ntwo" to caret(14), insert("one\ntwo", 4))
        assertEquals("![](a.jpg)\none" to caret(10), insert("one", 0))
    }

    @Test
    fun insertBlockMidLineGoesOnItsOwnLine() {
        assertEquals("on\n![](a.jpg)\ne" to caret(13), insert("one", 2))
    }

    @Test
    fun insertBlockAtALineEndAddsANewlineBefore() {
        assertEquals("one\n![](a.jpg)" to caret(14), insert("one", 3))
        assertEquals("one\n![](a.jpg)\ntwo" to caret(14), insert("one\ntwo", 3))
    }

    @Test
    fun insertBlockOnAnEmptyLineOrTextAddsNoNewline() {
        assertEquals("![](a.jpg)" to caret(10), insert("", 0))
        assertEquals("a\n![](a.jpg)\nb" to caret(12), insert("a\n\nb", 2))
    }

    @Test
    fun insertBlockClampsTheOffset() {
        assertEquals("one\n![](a.jpg)", insert("one", 99).first)
        assertEquals("![](a.jpg)\none", insert("one", -3).first)
    }

    @Test
    fun insertBlockKeepsTheBytesOutsideTheInsertion() {
        for (text in samples) {
            for (at in 0..text.length) {
                val e = TextOps.insertBlock(text, at, "![](x)")
                assertEquals(at, e.start)
                assertEquals(at, e.end)
                val after = text.apply(e)
                assertEquals(text, after.removeRange(at, at + e.replacement.length), "$text @$at")
            }
        }
    }
}
