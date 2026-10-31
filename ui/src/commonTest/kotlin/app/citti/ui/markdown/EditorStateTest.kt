package app.citti.ui.markdown

import app.citti.core.markdown.Selection
import app.citti.core.markdown.ToolbarAction
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A view that keeps its text in a string, like the platform view would, and calls back on user edits. */
private class FakeHost(
    private val state: () -> EditorState,
) : EditorHost {
    var text = ""
    var sel = Selection(0)
    val restyled = mutableListOf<IntRange>()

    override fun text() = text

    override fun replaceSilently(
        start: Int,
        end: Int,
        replacement: String,
        selection: Selection,
    ) {
        text = text.substring(0, start) + replacement + text.substring(end)
        sel = selection
    }

    override fun setAll(
        text: String,
        caret: Int,
    ) {
        this.text = text
        sel = Selection(caret)
    }

    override fun restyle(lines: IntRange) {
        restyled += lines
    }

    override fun selection() = sel

    override fun scrollToCaret() = Unit

    var top = 0

    override fun firstVisibleOffset() = top

    override fun scrollToOffset(offset: Int) {
        top = offset
    }

    /** What the user typing [s] at the caret does. */
    fun type(s: String) {
        val before = sel
        text = text.substring(0, before.start) + s + text.substring(before.end)
        sel = Selection(before.start + s.length)
        state().userChanged(before.start, "", s, before, sel)
    }
}

class EditorStateTest {
    private var now = 0L
    private lateinit var state: EditorState
    private lateinit var host: FakeHost

    private fun open(text: String = "") {
        state = EditorState({ now }, text)
        host = FakeHost { state }
        state.attach(host)
    }

    @Test
    fun attachShowsTheTextWithTheCaretAtTheEnd() {
        open("hello")
        assertEquals("hello", host.text)
        assertEquals(Selection(5), host.sel)
    }

    @Test
    fun typingKeepsTheLineIndexAndHistoryInStep() {
        open("a")
        host.type("b")
        assertEquals("ab", state.text)
        assertTrue(state.canUndo.value)
        state.undo()
        assertEquals("a", host.text)
        assertEquals("a", state.text)
        assertFalse(state.canUndo.value)
        assertTrue(state.canRedo.value)
        state.redo()
        assertEquals("ab", host.text)
    }

    @Test
    fun aToolbarActionIsOneUndoStep() {
        open("word")
        state.toolbar(ToolbarAction.Heading)
        assertEquals("# word", host.text)
        state.undo()
        assertEquals("word", host.text)
    }

    @Test
    fun theToolbarActionIsPressedAtTheCaret() {
        open("# title")
        state.selectionChanged()
        assertTrue(ToolbarAction.Heading in state.pressed.value)
    }

    @Test
    fun enterContinuesAList() {
        open("- one")
        val edit = state.enterEdit(Selection(5))
        assertEquals("\n- ", edit.replacement)
        state.apply(edit)
        assertEquals("- one\n- ", host.text)
        state.undo()
        assertEquals("- one", host.text)
    }

    @Test
    fun replaceAllClearsTheHistoryAndRecordsNothing() {
        open("a")
        host.type("b")
        state.replaceAll("server text", caret = 3)
        assertEquals("server text", host.text)
        assertEquals(Selection(3), host.sel)
        assertFalse(state.canUndo.value)
    }

    @Test
    fun aViewAttachedLaterGetsTheTextAndCaret() {
        state = EditorState({ now }, "later", caret = 2)
        host = FakeHost { state }
        state.attach(host)
        assertEquals(Selection(2), host.sel)
        assertEquals("later", host.text)
    }

    @Test
    fun aNoteOpensScrolledToItsEnd() {
        open("one\ntwo\nthree")
        assertEquals(13, host.top)
    }

    @Test
    fun theScrollPositionSurvivesTheViewAndAReplacementKeepsItsLine() {
        open("a\nb\nc\nd")
        state.scrollTo(4)
        assertEquals(4, host.top)
        state.detach(host)
        assertEquals(4, state.firstVisibleOffset())
        host = FakeHost { state }
        state.attach(host)
        assertEquals(4, host.top)
        // Line 2 stays at the top when the text is replaced.
        state.replaceAll("xx\nyy\nzz\nww", caret = 0)
        assertEquals(6, host.top)
    }

    @Test
    fun editsAreTheUsersAndReplaceAllIsNot() {
        open("a")
        var edits = 0
        var changes = 0
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        scope.launch { state.edits.collect { edits++ } }
        scope.launch { state.changes.collect { changes++ } }
        host.type("b")
        state.replaceAll("server")
        assertEquals(1, edits)
        assertEquals(2, changes)
        scope.cancel()
    }
}
