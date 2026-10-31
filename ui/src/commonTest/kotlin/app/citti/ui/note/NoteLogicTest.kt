package app.citti.ui.note

import app.citti.core.markdown.Block
import app.citti.core.markdown.Doc
import app.citti.core.markdown.ObsidianMarkdown
import app.citti.core.sync.SyncStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NoteLogicTest {
    @Test
    fun theSaveIndicatorFollowsPendingAndTheSyncStatus() {
        val idle = SyncStatus.Idle(null, null)
        assertEquals(SaveIndicator.Saved, saveIndicator(false, SyncStatus.Offline(null)))
        assertEquals(SaveIndicator.Pending, saveIndicator(true, idle))
        assertEquals(SaveIndicator.Pending, saveIndicator(true, null))
        assertEquals(SaveIndicator.Pending, saveIndicator(true, SyncStatus.AuthFailed(401, null)))
        assertEquals(SaveIndicator.Offline, saveIndicator(true, SyncStatus.Offline(null)))
        assertEquals(SaveIndicator.Failed, saveIndicator(true, SyncStatus.ServerError(500, null)))
        assertEquals(SaveIndicator.Failed, saveIndicator(true, SyncStatus.Failed("x", null)))
    }

    @Test
    fun linkRefsAreFoundInEveryBlockOnceEach() {
        val doc =
            ObsidianMarkdown.parse(
                "# See [[a/b]]\n\n[[a/b]] and [x](n.md) and [web](https://x.org)\n\n- item [[c|alias]]\n\n> quote [[d]]\n\n| h |\n|---|\n| [[e]] |\n",
            )
        assertEquals(
            listOf<LinkRef>(
                LinkRef.Wiki("a/b"),
                LinkRef.Markdown("n.md"),
                LinkRef.Wiki("c"),
                LinkRef.Wiki("d"),
                LinkRef.Wiki("e"),
            ),
            doc.linkRefs().toList(),
        )
    }

    @Test
    fun withToggledFlipsOnlyTheTaskAtTheOffset() {
        val text = "- [ ] one\n- [x] two\n\n> - [ ] quoted\n"
        val doc = ObsidianMarkdown.parse(text)
        val toggled = doc.withToggled(text.indexOf("[ ] one"))

        fun tasks(d: Doc) = d.blocks.filterIsInstance<Block.ListItem>().map { it.task?.checked }
        assertEquals(listOf(false, true), tasks(doc))
        assertEquals(listOf(true, true), tasks(toggled))
        // Source ranges are untouched.
        assertEquals(doc.blocks.map { it.start to it.end }, toggled.blocks.map { it.start to it.end })
        val inQuote = assertIs<Block.Quote>(doc.withToggled(text.indexOf("[ ] quoted")).blocks.last())
        assertEquals(true, assertIs<Block.ListItem>(inQuote.blocks.single()).task?.checked)
    }

    @Test
    fun blockIndexAtFindsTheBlockHoldingAnOffset() {
        val text = "# Title\n\npara one\n\n- a\n- b\n"
        val doc = ObsidianMarkdown.parse(text)
        assertEquals(0, doc.blockIndexAt(0))
        assertEquals(1, doc.blockIndexAt(text.indexOf("para")))
        assertEquals(1, doc.blockIndexAt(text.indexOf("one")))
        assertEquals(doc.blocks.lastIndex, doc.blockIndexAt(text.length - 1))
        assertEquals(0, ObsidianMarkdown.parse("").blockIndexAt(5))
    }

    @Test
    fun theCaretKeepsItsLineAndColumnAfterAReload() {
        val old = "one\ntwo words\nthree"
        // Line 1, column 4.
        assertEquals(8, caretAfterReload(old, 8, "uno\ndos más\nlast"))
        // The new line is shorter: the end of that line.
        assertEquals(6, caretAfterReload(old, 8, "uno\nab\nlast"))
        // Fewer lines: the last line.
        assertEquals(3, caretAfterReload(old, old.length, "abc"))
        assertEquals(0, caretAfterReload("", 0, ""))
        assertEquals(4, caretAfterReload(old, 4, "uno\ndos"))
    }
}
