package app.citti.core.markdown

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LineLexerTest {
    private fun lex(
        line: String,
        state: LineState = LineState.Normal,
        index: Int = 1,
    ) = LineLexer.lexLine(line, state, index)

    /** The text of each span of [kind]. */
    private fun texts(
        line: String,
        kind: SpanKind,
        state: LineState = LineState.Normal,
    ) = lex(line, state).spans.filter { it.kind == kind }.map { line.substring(it.start, it.end) }

    @Test
    fun headingsOneToSix() {
        for (n in 1..6) {
            val line = "#".repeat(n) + " Title"
            val l = lex(line)
            assertEquals(n, l.info.headingLevel)
            assertEquals(listOf("#".repeat(n)), texts(line, SpanKind.HeadingMark))
            assertEquals(listOf("Title"), texts(line, SpanKind.HeadingText))
            assertEquals(n, l.spans.first { it.kind == SpanKind.HeadingText }.level)
        }
        assertEquals(0, lex("####### seven").info.headingLevel)
    }

    @Test
    fun aTagIsNotAHeading() {
        assertEquals(0, lex("#tag and more").info.headingLevel)
        assertTrue(lex("#tag").spans.isEmpty())
    }

    @Test
    fun boldItalicStrikeHighlight() {
        assertEquals(listOf("**bold**"), texts("a **bold** b", SpanKind.Bold))
        assertEquals(listOf("__bold__"), texts("a __bold__ b", SpanKind.Bold))
        assertEquals(listOf("*it*"), texts("a *it* b", SpanKind.Italic))
        assertEquals(listOf("_it_"), texts("a _it_ b", SpanKind.Italic))
        assertEquals(listOf("~~gone~~"), texts("a ~~gone~~ b", SpanKind.Strike))
        assertEquals(listOf("==hot=="), texts("a ==hot== b", SpanKind.Highlight))
        assertEquals(listOf("**", "**"), texts("a **bold** b", SpanKind.Mark))
    }

    @Test
    fun snakeCaseAndProductsStayPlain() {
        assertTrue(lex("snake_case_word and 2*3*4").spans.isEmpty())
        assertTrue(lex("a * b * c").spans.isEmpty())
    }

    @Test
    fun nestedEmphasis() {
        val line = "**a _b_ c**"
        assertEquals(listOf("**a _b_ c**"), texts(line, SpanKind.Bold))
        assertEquals(listOf("_b_"), texts(line, SpanKind.Italic))
    }

    @Test
    fun inlineCodeHidesOtherMarks() {
        val line = "see `**x** [[y]]` now"
        assertEquals(listOf("`**x** [[y]]`"), texts(line, SpanKind.InlineCode))
        assertTrue(texts(line, SpanKind.Bold).isEmpty())
        assertTrue(texts(line, SpanKind.WikilinkTarget).isEmpty())
    }

    @Test
    fun fences() {
        val open = lex("```kotlin")
        assertEquals(LineState.InFence('`', 3), open.endState)
        assertEquals(SpanKind.CodeFence, open.spans.single().kind)
        val body = lex("val x = **1**", open.endState)
        assertEquals(SpanKind.CodeBlock, body.spans.single().kind)
        assertEquals(open.endState, body.endState)
        val close = lex("```", open.endState)
        assertEquals(LineState.Normal, close.endState)
        assertEquals(SpanKind.CodeFence, close.spans.single().kind)
        // A shorter run does not close a longer fence, and a backtick fence's info cannot hold a backtick.
        assertEquals(LineState.InFence('`', 4), lex("````").endState)
        assertEquals(LineState.InFence('`', 4), lex("```", LineState.InFence('`', 4)).endState)
        assertEquals(LineState.Normal, lex("``` not a fence`").endState)
        assertEquals(LineState.InFence('~', 3), lex("~~~").endState)
    }

    @Test
    fun anUnclosedFenceRunsToTheEnd() {
        val doc = MarkdownDocument("a\n```\nb\nc")
        assertEquals(
            SpanKind.CodeBlock,
            doc
                .lexedLine(3)
                .spans
                .single()
                .kind,
        )
    }

    @Test
    fun frontmatterOnlyOnLineZero() {
        val doc = MarkdownDocument("---\ntitle: x\n...\nbody **b**")
        assertEquals(
            SpanKind.Frontmatter,
            doc
                .lexedLine(0)
                .spans
                .single()
                .kind,
        )
        assertEquals(
            SpanKind.Frontmatter,
            doc
                .lexedLine(1)
                .spans
                .single()
                .kind,
        )
        assertEquals(
            SpanKind.Frontmatter,
            doc
                .lexedLine(2)
                .spans
                .single()
                .kind,
        )
        assertEquals(
            SpanKind.Bold,
            doc
                .lexedLine(3)
                .spans
                .first { it.kind == SpanKind.Bold }
                .kind,
        )
        val rule = MarkdownDocument("text\n---\nmore")
        assertTrue(rule.lexedLine(1).spans.isEmpty())
        // Frontmatter closed by --- too.
        assertEquals(LineState.Normal, MarkdownDocument("---\na\n---\nb").lexedLine(2).endState)
    }

    @Test
    fun wikilinks() {
        val plain = "see [[Note]] x"
        assertEquals(listOf("Note"), texts(plain, SpanKind.WikilinkTarget))
        assertEquals(listOf("[[", "]]"), texts(plain, SpanKind.WikilinkMark))
        val alias = "[[folder/Note|shown]]"
        assertEquals(listOf("folder/Note"), texts(alias, SpanKind.WikilinkTarget))
        assertEquals(listOf("shown"), texts(alias, SpanKind.WikilinkAlias))
        assertEquals(listOf("|"), texts(alias, SpanKind.Mark))
        val embed = "![[pic.png|300]]"
        assertEquals(listOf("!"), texts(embed, SpanKind.ImageMark))
        assertEquals(listOf("pic.png"), texts(embed, SpanKind.WikilinkTarget))
        assertEquals(listOf("Note#Heading"), texts("[[Note#Heading]]", SpanKind.WikilinkTarget))
        assertTrue(texts("[[unclosed", SpanKind.WikilinkTarget).isEmpty())
    }

    @Test
    fun markdownLinksImagesAndUrls() {
        val link = "go [the site](https://a.example/x_y_z) ok"
        assertEquals(listOf("the site"), texts(link, SpanKind.LinkText))
        assertEquals(listOf("https://a.example/x_y_z"), texts(link, SpanKind.LinkTarget))
        assertTrue(texts(link, SpanKind.Italic).isEmpty())
        val image = "![alt|900](a/b.png)"
        assertEquals(listOf(image), texts(image, SpanKind.ImageMark))
        assertEquals(listOf("https://x.example/p?q=1"), texts("see https://x.example/p?q=1.", SpanKind.Url))
        assertEquals(listOf("https://x.example"), texts("(see https://x.example)", SpanKind.Url))
    }

    @Test
    fun listMarkersAndTasks() {
        val dash = lex("- item").info
        assertEquals("-", dash.marker)
        assertEquals(2, dash.markerEnd)
        assertEquals(2, dash.contentStart)
        assertEquals("*", lex("* x").info.marker)
        assertEquals("+", lex("+ x").info.marker)
        assertEquals("1.", lex("1. x").info.marker)
        assertEquals("12)", lex("12) x").info.marker)
        val nested = lex("    - deep").info
        assertEquals(4, nested.indent)
        assertEquals("-", nested.marker)
        assertEquals(null, lex("**bold** start").info.marker)
        assertEquals(null, lex("---").info.marker)

        val open = lex("- [ ] todo")
        assertEquals(TaskState.Open, open.info.task)
        assertEquals(6, open.info.contentStart)
        assertEquals(listOf("[ ]"), texts("- [ ] todo", SpanKind.TaskMark))
        for (box in listOf("[x]", "[X]")) {
            val line = "- $box done"
            assertEquals(TaskState.Done, lex(line).info.task)
            assertEquals(listOf("done"), texts(line, SpanKind.TaskDone))
        }
        assertEquals(null, lex("- [y] no").info.task)
    }

    @Test
    fun quote() {
        assertEquals(listOf(">"), texts("> quoted", SpanKind.Quote))
        assertEquals(listOf(">", ">"), texts("> > deeper", SpanKind.Quote))
        assertEquals("-", lex("> - item").info.marker)
    }

    @Test
    fun aCarriageReturnIsNotText() {
        val l = lex("# Title\r")
        assertEquals(1, l.info.headingLevel)
        assertEquals(Span(2, 7, SpanKind.HeadingText, 1), l.spans.last())
    }

    @Test
    fun aHugeLineGetsNoInlineSpans() {
        val l = lex("**x** ".repeat(2_000))
        assertTrue(l.spans.isEmpty())
    }

    // ---- incremental ----

    private fun sample() =
        """
        ---
        title: Sample
        tags: [a, b]
        ---
        # Heading **one**

        Some _text_ with a [[Link|alias]] and `code`.

        - [ ] task one
        - [x] task two
          - nested ~~gone~~
        1. first
        2. second

        > quote with ==mark==

        ```kotlin
        val x = 1
        ```

        Last line https://example.com/page.
        """.trimIndent()

    @Test
    fun theWholeNoteLexesLikeLineByLine() {
        val doc = MarkdownDocument(sample())
        var state: LineState = LineState.Normal
        sample().split('\n').forEachIndexed { i, line ->
            val l = LineLexer.lexLine(line, state, i)
            assertEquals(l, doc.lexedLine(i), "line $i")
            state = l.endState
        }
    }

    @Test
    fun incrementalEqualsFullRelexForRandomEdits() {
        val rnd = Random(20261031)
        val pieces =
            listOf(
                "a",
                "b ",
                "**",
                "_",
                "`",
                "```",
                "\n",
                "\n\n",
                "# ",
                "- ",
                "- [ ] ",
                "[[",
                "]]",
                "|",
                "~~",
                "==",
                "---",
                "x",
                " ",
                "\n---\n",
                "> ",
                "1. ",
                "http://x.y",
                "é",
            )
        var text = sample()
        val doc = MarkdownDocument(text)
        repeat(500) { case ->
            val a = rnd.nextInt(text.length + 1)
            val b = if (rnd.nextInt(3) == 0) minOf(text.length, a + rnd.nextInt(12)) else a
            val replacement =
                if (rnd.nextInt(4) ==
                    0
                ) {
                    ""
                } else {
                    pieces[rnd.nextInt(pieces.size)] + if (rnd.nextBoolean()) pieces[rnd.nextInt(pieces.size)] else ""
                }
            text = text.substring(0, a) + replacement + text.substring(b)
            doc.replace(a, b, replacement)
            assertEquals(text, doc.text(), "text, case $case")
            assertEquals(
                MarkdownDocument(text).snapshot(),
                doc.snapshot(),
                "lexing, case $case after replacing $a..$b with ${replacement.length} chars",
            )
        }
    }

    @Test
    fun typingALetterRelexesOneLineAndAFenceRelexesToTheEnd() {
        val doc = MarkdownDocument(sample())
        val line = doc.lineOf(sample().indexOf("Some"))
        assertEquals(line..line, doc.replace(doc.lineStart(line), doc.lineStart(line), "x"))
        // Opening a fence at line 5 changes the state of every line below it until another fence closes it.
        val range = doc.replace(doc.lineStart(5), doc.lineStart(5), "```\n")
        assertEquals(5, range.first)
        assertTrue(range.last > 8, "fence runs on: $range")
    }
}
