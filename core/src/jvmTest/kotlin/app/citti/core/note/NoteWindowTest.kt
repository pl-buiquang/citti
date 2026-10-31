package app.citti.core.note

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoteWindowTest {
    private fun text(n: Int) = (0 until n).joinToString("\n") { "line$it" }

    @Test
    fun startsAtTheReadingPosition() {
        val w = NoteWindows.slice(text(100), viewLine = 10)
        assertEquals(10, w.firstLine)
        assertEquals("line10", w.lines.first())
        assertEquals(90, w.lines.size)
        assertFalse(w.more)
    }

    @Test
    fun capsTheLineCount() {
        val w = NoteWindows.slice(text(500), viewLine = 0)
        assertEquals(NoteWindows.MAX_LINES, w.lines.size)
        assertTrue(w.more)
    }

    @Test
    fun capsTheCharacters() {
        val long = "x".repeat(6_000)
        val w = NoteWindows.slice("$long\n$long\nend", viewLine = 0)
        assertEquals(listOf(long), w.lines)
        assertTrue(w.more)
    }

    @Test
    fun cutsASingleLineOverTheCap() {
        val w = NoteWindows.slice("y".repeat(25_000) + "\nz", viewLine = 0)
        assertEquals(NoteWindows.MAX_CHARS, w.lines.single().length)
        assertTrue(w.more)
    }

    @Test
    fun nullNegativeAndPastTheEndGiveTheTail() {
        val content = text(100)
        for (position in listOf(null, -3, 99, 250)) {
            val w = NoteWindows.slice(content, position)
            assertEquals(40, w.firstLine)
            assertEquals(NoteWindows.TAIL_LINES, w.lines.size)
            assertFalse(w.more)
        }
    }

    @Test
    fun aNoteShorterThanTheTailIsShownWhole() {
        val w = NoteWindows.slice(text(5), viewLine = null)
        assertEquals(0, w.firstLine)
        assertEquals(5, w.lines.size)
    }

    @Test
    fun emptyContentIsOneEmptyLine() {
        val w = NoteWindows.slice("", viewLine = 0)
        assertEquals(listOf(""), w.lines)
        assertFalse(w.more)
    }

    @Test
    fun aTrailingNewlineIsAnEmptyLastLine() {
        val w = NoteWindows.slice("a\nb\n", viewLine = 0)
        assertEquals(listOf("a", "b", ""), w.lines)
        assertFalse(w.more)
    }
}
