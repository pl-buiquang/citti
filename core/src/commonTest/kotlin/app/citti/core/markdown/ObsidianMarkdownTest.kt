package app.citti.core.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObsidianMarkdownTest {
    private fun parse(text: String) = ObsidianMarkdown.parse(text).blocks

    private fun inlines(text: String): List<Inline> = assertIs<Block.Paragraph>(parse(text).single()).inlines

    @Test
    fun extraBlankLinesBetweenParagraphsAreCounted() {
        fun gaps(text: String) = (0 until ObsidianMarkdown.parse(text).blocks.size).map { ObsidianMarkdown.parse(text).gapBefore(it) }
        assertEquals(listOf(0, 0), gaps("a\n\nb"))
        assertEquals(listOf(0, 1), gaps("a\n\n\nb"))
        assertEquals(listOf(0, 2), gaps("a\n\n\n\nb"))
        assertEquals(listOf(0, 0, 1), gaps("# h\nb\n\n\nc").let { listOf(0, 0, it.last()) })
    }

    @Test
    fun blankLinesNextToListsAndCodeAreNotCounted() {
        val doc = ObsidianMarkdown.parse("a\n\n\n- x\n\n\n```\ncode\n\n\nmore\n```\n\n\nb")
        assertEquals(List(doc.blocks.size) { 0 }, List(doc.blocks.size) { doc.gapBefore(it) })
    }

    @Test
    fun everyBlockKind() {
        val blocks =
            parse(
                """
                # Title

                Setext
                ======

                A paragraph.

                > quoted

                ---

                ```kotlin
                val x = 1
                ```

                | a | b |
                |:--|--:|
                | 1 | 2 |

                - item
                1. numbered
                """.trimIndent(),
            )
        assertEquals(
            listOf("Heading", "Heading", "Paragraph", "Quote", "Rule", "Code", "Table", "ListItem", "ListItem"),
            blocks.map { it::class.simpleName },
        )
        assertEquals(1, assertIs<Block.Heading>(blocks[0]).level)
        assertEquals(1, assertIs<Block.Heading>(blocks[1]).level)
        assertEquals("Setext", (blocks[1] as Block.Heading).inlines.plainText())
        assertEquals("kotlin", assertIs<Block.Code>(blocks[5]).info)
        assertEquals("val x = 1", (blocks[5] as Block.Code).text)
        assertEquals(false, assertIs<Block.ListItem>(blocks[7]).ordered)
        assertEquals(1, assertIs<Block.ListItem>(blocks[8]).number)
    }

    @Test
    fun tableAlignmentAndCells() {
        val t = assertIs<Block.Table>(parse("| a | b | c |\n|:--|:-:|--:|\n| 1 | **2** | 3 |").single())
        assertEquals(listOf(Align.Left, Align.Center, Align.Right), t.align)
        assertEquals(listOf("a", "b", "c"), t.header.map { it.plainText() })
        assertEquals(listOf("1", "2", "3"), t.rows.single().map { it.plainText() })
        assertIs<Inline.Bold>(t.rows.single()[1].single())
    }

    @Test
    fun nestedListsAndTasks() {
        val text = "- [ ] one\n  - nested\n    - deeper\n- [x] two\n"
        val items = parse(text).map { assertIs<Block.ListItem>(it) }
        assertEquals(listOf(0, 1, 2, 0), items.map { it.depth })
        assertEquals(listOf(false, true), listOf(items[0], items[3]).map { it.task!!.checked })
        assertNull(items[1].task)
        assertEquals("nested", items[1].inlines.plainText())
        for (i in items.filter { it.task != null }) {
            assertEquals(
                text.substring(i.task!!.box, i.task.box + 3),
                if (i.task.checked) "[x]" else "[ ]",
            )
        }
    }

    @Test
    fun frontmatterIsFoldedAndParsed() {
        val text = "---\ntitle: My note\ntags:\n  - a\n  - \"b c\"\naliases: [x, y]\n---\n# Body"
        val blocks = parse(text)
        val props = assertIs<Block.Properties>(blocks[0])
        assertEquals(
            listOf(Property("title", listOf("My note")), Property("tags", listOf("a", "b c")), Property("aliases", listOf("x", "y"))),
            props.entries,
        )
        assertEquals("Body", assertIs<Block.Heading>(blocks[1]).inlines.plainText())
        assertEquals(text.indexOf("# Body"), blocks[1].start)
    }

    @Test
    fun invalidFrontmatterIsShownRaw() {
        val props = assertIs<Block.Properties>(parse("---\nthis is: fine\n{not yaml\n---\nbody")[0])
        assertNull(props.entries)
        assertEquals("this is: fine\n{not yaml", props.raw)
    }

    @Test
    fun anUnclosedOrNonFirstFrontmatterIsNotFrontmatter() {
        assertEquals(listOf("Rule", "Paragraph"), parse("---\nnot closed").map { it::class.simpleName })
        assertTrue(parse("text\n---\nkey: v\n---\n").none { it is Block.Properties })
    }

    @Test
    fun wikilinkForms() {
        val i = inlines("See [[Note]], [[folder/Other|shown]] and [[Note#Heading]] ok")
        assertEquals(
            listOf(Inline.Wikilink("Note", null), Inline.Wikilink("folder/Other", "shown"), Inline.Wikilink("Note#Heading", null)),
            i.filterIsInstance<Inline.Wikilink>(),
        )
        assertEquals("See Note, shown and Heading ok".replace("Heading", "Note#Heading"), i.plainText())
    }

    @Test
    fun aWikilinkWithAPipeInATableCellStaysOneCell() {
        val t = assertIs<Block.Table>(parse("| a | b |\n|---|---|\n| [[x|y]] | z |").single())
        assertEquals(2, t.rows.single().size)
        assertEquals(Inline.Wikilink("x", "y"), t.rows.single()[0].single())
    }

    @Test
    fun embedsAndImages() {
        assertEquals(Inline.Image("pic.png", "pic.png", null, null), (parse("![[pic.png]]").single() as Block.ImageBlock).images.single())
        assertEquals(
            Inline.Image("a/pic.png", "pic.png", 300, null),
            (parse("![[a/pic.png|300]]").single() as Block.ImageBlock).images.single(),
        )
        assertEquals(Inline.Embed("doc.pdf"), inlines("see ![[doc.pdf]] now").filterIsInstance<Inline.Embed>().single())
        assertEquals(Inline.Embed("draw.excalidraw"), inlines("![[draw.excalidraw]] x").first())
        val md = (parse("![Page 87|900](supernote/a.png)").single() as Block.ImageBlock).images.single()
        assertEquals(Inline.Image("supernote/a.png", "Page 87", 900, null), md)
        val both = (parse("![a|900x300](u.png)").single() as Block.ImageBlock).images.single()
        assertEquals(900, both.width)
        assertEquals(300, both.height)
        assertEquals("a", both.alt)
        val width = (parse("![900](u.png)").single() as Block.ImageBlock).images.single()
        assertEquals(900, width.width)
    }

    @Test
    fun anImageAmongTextIsInline() {
        val p = assertIs<Block.Paragraph>(parse("look ![a](u.png) here").single())
        assertTrue(p.inlines.any { it is Inline.Image })
    }

    @Test
    fun highlight() {
        assertEquals(Inline.Highlight(listOf(Inline.Text("hot"))), inlines("a ==hot== b")[1])
    }

    @Test
    fun linksToNotesAndTheWeb() {
        val i = inlines("[plan](action-plan.md) [sub](../x/y.md#h) [web](https://a.example/b.md) https://auto.example/z")
        assertEquals(Inline.NoteLink("action-plan.md", listOf(Inline.Text("plan"))), i[0])
        assertEquals("../x/y.md#h", (i[2] as Inline.NoteLink).path)
        assertEquals(Inline.Link("https://a.example/b.md", listOf(Inline.Text("web"))), i[4])
        assertEquals("https://auto.example/z", (i.last() as Inline.Link).url)
    }

    @Test
    fun emphasisAndCode() {
        val i = inlines("**b** _i_ ~~s~~ `c`")
        assertEquals(listOf("Bold", "Text", "Italic", "Text", "Strike", "Text", "Code"), i.map { it::class.simpleName })
        assertEquals("c", (i.last() as Inline.Code).text)
    }

    @Test
    fun htmlIsKeptAsText() {
        assertEquals("<div>x</div>", assertIs<Block.Paragraph>(parse("<div>x</div>").single()).inlines.plainText())
        assertTrue(inlines("a <b>bold</b> c").plainText().contains("<b>"))
    }

    @Test
    fun mathIsText() {
        assertEquals("$$ x^2 $$", inlines("$$ x^2 $$").plainText())
    }

    @Test
    fun mermaidIsACodeBlock() {
        assertEquals("mermaid", assertIs<Block.Code>(parse("```mermaid\ngraph TD\n```").single()).info)
    }

    @Test
    fun codeIsNotWikilinked() {
        val c = assertIs<Block.Code>(parse("```\n[[not a link]]\n```").single())
        assertEquals("[[not a link]]", c.text)
        assertEquals(Inline.Code("[[no]]"), inlines("`[[no]]`").single())
    }

    @Test
    fun taskOffsetsPointAtTheCheckbox() {
        for (nl in listOf("\n", "\r\n")) {
            for (front in listOf("", "---${nl}title: x$nl---$nl")) {
                val text = front + "# T$nl$nl- [ ] open$nl- [x] done$nl  - [ ] sub$nl"
                val tasks = parse(text).filterIsInstance<Block.ListItem>().mapNotNull { it.task }
                assertEquals(3, tasks.size, "nl=${nl.length} front=${front.isNotEmpty()}")
                assertEquals(listOf("[ ]", "[x]", "[ ]"), tasks.map { text.substring(it.box, it.box + 3) })
            }
        }
    }

    private fun realisticNote(n: Int): String {
        val sb = StringBuilder()
        if (n % 3 == 0) sb.append("---\ntags: [a, b]\n---\n")
        sb.append("# Note $n\n\nIntro with [[Link $n]] and **bold**.\nSecond line.\n\n")
        for (k in 0 until 4 + n % 5) sb.append("- item $k with ![[img$k.png|200]]\n  - sub $k\n")
        sb.append("\n> quote $n\n\n```\ncode $n\n```\n\n| a | b |\n|---|---|\n| [[x|y]] | $n |\n\n---\n\nTail paragraph.\n")
        return sb.toString()
    }

    @Test
    fun blocksCoverTheTextInOrderWithoutLoss() {
        for (n in 0 until 50) {
            val text = realisticNote(n)
            val blocks = parse(text)
            var pos = 0
            for (b in blocks) {
                assertTrue(b.start >= pos, "note $n: block ${b::class.simpleName} starts at ${b.start} before $pos")
                assertTrue(b.end >= b.start && b.end <= text.length, "note $n: bad range ${b.start}..${b.end}")
                val gap = text.substring(pos, b.start)
                assertTrue(gap.isBlank(), "note $n: text lost between blocks: '$gap'")
                pos = b.end
            }
            assertTrue(text.substring(pos).isBlank(), "note $n: tail lost: '${text.substring(pos)}'")
        }
    }

    @Test
    fun aNineThousandItemListAndALongFenceAreChunked() {
        val list = (1..9_000).joinToString("\n") { "- [link $it](https://x.example/$it)" }
        val items = parse(list)
        assertEquals(9_000, items.size)
        assertTrue(items.all { it is Block.ListItem })

        val fence = "```\n" + (1..500).joinToString("\n") { "line $it" } + "\n```"
        val chunks = parse(fence).map { assertIs<Block.Code>(it) }
        assertEquals((500 + CHUNK_LINES - 1) / CHUNK_LINES, chunks.size)
        assertEquals(listOf(false) + List(chunks.size - 1) { true }, chunks.map { it.continued })
        assertEquals((1..500).joinToString("\n") { "line $it" }, chunks.joinToString("\n") { it.text })
        var pos = 0
        for (c in chunks) {
            assertTrue(c.start >= pos)
            assertTrue(fence.substring(pos, c.start).isBlank())
            pos = c.end
        }
        assertEquals(fence.length, pos)

        val paragraph = (1..200).joinToString("\n") { "word $it" }
        val parts = parse(paragraph).map { assertIs<Block.Paragraph>(it) }
        assertTrue(parts.size > 1)
        assertEquals(paragraph, parts.joinToString("\n") { it.inlines.plainText() })
        assertEquals(0, parts.first().start)
        assertEquals(paragraph.length, parts.last().end)
        for (k in 1 until parts.size) assertTrue(paragraph.substring(parts[k - 1].end, parts[k].start).isBlank())
    }

    @Test
    fun plainTextOfBlocksForSearch() {
        val b = parse("# Hello **world**\n\n- [[Note|alias]] text")
        assertEquals("Hello world", b[0].plainText())
        assertEquals("alias text", b[1].plainText())
        assertNotNull(b[1])
    }
}
