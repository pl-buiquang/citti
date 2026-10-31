package app.citti.core.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorHistoryTest {
    private var text = ""
    private val history = EditorHistory(pauseMs = 1_000)

    private fun type(
        s: String,
        at: Long,
    ) {
        val start = text.length
        text += s
        history.record(HistoryStep(start, "", s, Selection(start), Selection(text.length)), at)
    }

    private fun backspace(at: Long) {
        val removed = text.last().toString()
        text = text.dropLast(1)
        history.record(HistoryStep(text.length, removed, "", Selection(text.length + 1), Selection(text.length)), at)
    }

    private fun act(edit: Edit) {
        text = text.apply(edit)
    }

    private fun undo() = act(history.undo()!!.undoEdit())

    private fun redo() = act(history.redo()!!.redoEdit())

    @Test
    fun typingAWordIsOneStep() {
        "hello".forEachIndexed { i, c -> type(c.toString(), i * 100L) }
        undo()
        assertEquals("", text)
        assertFalse(history.canUndo)
    }

    @Test
    fun aSpaceEndsTheWord() {
        var t = 0L
        "ab cd".forEach { type(it.toString(), t.also { t += 100 }) }
        undo()
        assertEquals("ab ", text)
        undo()
        assertEquals("ab", text)
        undo()
        assertEquals("", text)
    }

    @Test
    fun aPauseEndsTheStep() {
        type("a", 0)
        type("b", 100)
        type("c", 2_000)
        undo()
        assertEquals("ab", text)
    }

    @Test
    fun backspacesAreOneStep() {
        type("hello", 0)
        backspace(100)
        backspace(200)
        backspace(300)
        assertEquals("he", text)
        undo()
        assertEquals("hello", text)
    }

    @Test
    fun undoThenRedoGivesTheSameText() {
        type("one", 0)
        type(" ", 100)
        type("two", 200)
        val before = text
        undo()
        undo()
        redo()
        redo()
        assertEquals(before, text)
        assertFalse(history.canRedo)
    }

    @Test
    fun anAtomicEditIsItsOwnStepAndEndsTyping() {
        type("ab", 0)
        val edit = Edit(0, 2, "**ab**", Selection(6))
        history.record(HistoryStep(0, "ab", "**ab**", Selection(0, 2), Selection(6)), 50)
        act(edit)
        type("c", 100)
        undo()
        assertEquals("**ab**", text)
        undo()
        assertEquals("ab", text)
        undo()
        assertEquals("", text)
    }

    @Test
    fun aNewChangeDropsTheRedoSteps() {
        type("a", 0)
        undo()
        assertTrue(history.canRedo)
        type("b", 10)
        assertFalse(history.canRedo)
    }

    @Test
    fun clearEmptiesBothStacks() {
        type("a", 0)
        undo()
        history.clear()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertNull(history.undo())
    }

    @Test
    fun theHistoryIsBounded() {
        val h = EditorHistory(limit = 3)
        repeat(10) { h.record(HistoryStep(0, "", "x ", Selection(0), Selection(2)), it * 10L) }
        repeat(3) { assertTrue(h.undo() != null) }
        assertNull(h.undo())
    }
}
