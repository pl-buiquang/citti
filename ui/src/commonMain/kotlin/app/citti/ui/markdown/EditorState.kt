package app.citti.ui.markdown

import app.citti.core.markdown.Edit
import app.citti.core.markdown.EditorHistory
import app.citti.core.markdown.HistoryStep
import app.citti.core.markdown.MarkdownDocument
import app.citti.core.markdown.Selection
import app.citti.core.markdown.TextOps
import app.citti.core.markdown.ToolbarAction
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The platform text view, as the editor logic sees it. [EditorState] calls these; the view calls back into
 * [EditorState.userChanged], [EditorState.selectionChanged] and [EditorState.focusChanged].
 */
interface EditorHost {
    fun text(): String

    /** Replaces text and selection without telling [EditorState] about it (it already knows). */
    fun replaceSilently(
        start: Int,
        end: Int,
        replacement: String,
        selection: Selection,
    )

    /** Replaces the whole text, selects [caret] and styles every line. */
    fun setAll(
        text: String,
        caret: Int,
    )

    /** Applies the spans of these lines of [EditorState.document] again. */
    fun restyle(lines: IntRange)

    fun selection(): Selection

    fun scrollToCaret()
}

/**
 * The text of the open Note while its screen is up: the line index ([document]), the undo history and the
 * selection, independent of the view, which comes and goes with the composition. Edits from the user, the toolbar,
 * Enter and undo all end in [committed], so the index, the spans, the history and [changes] stay in step.
 */
class EditorState(
    private val clockMs: () -> Long,
    text: String = "",
    caret: Int = text.length,
) {
    var document = MarkdownDocument(text)
        private set

    private var host: EditorHost? = null
    private var pendingCaret = caret
    private val history = EditorHistory()

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits after each change of the text, whoever made it. */
    val changes: SharedFlow<Unit> = _changes

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo

    private val _pressed = MutableStateFlow<Set<ToolbarAction>>(emptySet())

    /** The toolbar actions whose syntax is active at the caret. */
    val pressed: StateFlow<Set<ToolbarAction>> = _pressed

    private val _focused = MutableStateFlow(false)
    val focused: StateFlow<Boolean> = _focused

    val text: String get() = document.text()

    val selection: Selection get() = host?.selection() ?: Selection(pendingCaret.coerceIn(0, document.length))

    fun attach(host: EditorHost) {
        this.host = host
        host.setAll(document.text(), pendingCaret.coerceIn(0, document.length))
    }

    fun detach(host: EditorHost) {
        if (this.host === host) {
            pendingCaret = host.selection().start
            this.host = null
        }
    }

    /** A live update or the first load: new text, history cleared, nothing recorded. */
    fun replaceAll(
        text: String,
        caret: Int = text.length,
    ) {
        document = MarkdownDocument(text)
        history.clear()
        pendingCaret = caret.coerceIn(0, text.length)
        host?.setAll(text, pendingCaret)
        publishHistory()
        updatePressed()
        _changes.tryEmit(Unit)
    }

    /** The view changed its text because of the user (typing, paste, IME). The text is already changed there. */
    fun userChanged(
        start: Int,
        removed: String,
        inserted: String,
        selectionBefore: Selection,
        selectionAfter: Selection,
    ) {
        committed(start, removed, inserted, selectionBefore, selectionAfter, record = true)
    }

    fun selectionChanged() = updatePressed()

    fun focusChanged(focused: Boolean) {
        _focused.value = focused
    }

    /** Applies [edit] to the view as one undo step. */
    fun apply(edit: Edit) = applyEdit(edit, record = true)

    fun undo() {
        val step = history.undo() ?: return
        applyEdit(step.undoEdit(), record = false)
        publishHistory()
    }

    fun redo() {
        val step = history.redo() ?: return
        applyEdit(step.redoEdit(), record = false)
        publishHistory()
    }

    /** Runs a toolbar button. Returns false when it did nothing (no view yet). */
    fun toolbar(action: ToolbarAction): Boolean {
        if (host == null) return false
        val sel = selection
        val edit =
            when (action) {
                ToolbarAction.Bold -> TextOps.bold(document, sel)
                ToolbarAction.Italic -> TextOps.italic(document, sel)
                ToolbarAction.Heading -> TextOps.heading(document, sel)
                ToolbarAction.List -> TextOps.list(document, sel)
                ToolbarAction.Checkbox -> TextOps.checkbox(document, sel)
                ToolbarAction.Link -> TextOps.link(document, sel)
            }
        apply(edit)
        return true
    }

    /** The Enter key: [TextOps.enter] against the document as it was before the newline. */
    fun enterEdit(selection: Selection): Edit = TextOps.enter(document, selection)

    private fun applyEdit(
        edit: Edit,
        record: Boolean,
    ) {
        val h = host ?: return
        val removed = document.text().substring(edit.start, edit.end)
        val before = h.selection()
        h.replaceSilently(edit.start, edit.end, edit.replacement, edit.selection)
        committed(edit.start, removed, edit.replacement, before, edit.selection, record)
        h.scrollToCaret()
    }

    private fun committed(
        start: Int,
        removed: String,
        inserted: String,
        selectionBefore: Selection,
        selectionAfter: Selection,
        record: Boolean,
    ) {
        val lines = document.replace(start, start + removed.length, inserted)
        host?.restyle(lines)
        if (record) {
            history.record(HistoryStep(start, removed, inserted, selectionBefore, selectionAfter), clockMs())
            publishHistory()
        }
        updatePressed()
        _changes.tryEmit(Unit)
    }

    private fun publishHistory() {
        _canUndo.value = history.canUndo
        _canRedo.value = history.canRedo
    }

    private fun updatePressed() {
        val h = host
        _pressed.value = if (h == null) emptySet() else TextOps.pressed(document, h.selection())
    }
}
