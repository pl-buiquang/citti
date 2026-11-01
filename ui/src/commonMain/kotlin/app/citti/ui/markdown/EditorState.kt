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

    /** Source offset of the first line that shows at the top of the view. */
    fun firstVisibleOffset(): Int

    /** Scrolls so that the line holding [offset] is at the top (as far as the text allows). */
    fun scrollToOffset(offset: Int)

    /** Takes the focus and raises the keyboard. */
    fun focusAndShowKeyboard() = Unit

    /**
     * A [Marker] at the caret that the view moves with its own edits (Android: a zero-length span). Null when the
     * view has none; [EditorState] tracks its markers itself either way, the view's one wins while it is attached.
     */
    fun markCaret(): Marker? = null
}

/**
 * A place in the text that moves with the edits around it, like a zero-length `SPAN_POINT_POINT` span: text
 * inserted at it goes before it. Where an uploaded Image's link goes. [release] it when done.
 */
interface Marker {
    val offset: Int

    fun release()
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

    // Where the view scrolls to when it attaches; null is the caret (a Note opens at its end).
    private var pendingScroll: Int? = null

    // Set by [requestFocus] before the view attaches.
    private var pendingFocus = false

    /** A double tap in the view (Edit mode): the screen switches mode. Null: the view keeps the platform behaviour. */
    var onDoubleTap: (() -> Unit)? = null
    private val history = EditorHistory()

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits after each change of the text, whoever made it. */
    val changes: SharedFlow<Unit> = _changes

    private val _edits = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits after each change made by the user (typing, toolbar, Enter, undo, redo), not by [replaceAll]: the saves follow these. */
    val edits: SharedFlow<Unit> = _edits

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

    private val markers = mutableListOf<TrackedMarker>()

    /** A marker tracked here, so it outlives the view; [onHost] is the view's own while that view is attached. */
    private inner class TrackedMarker(
        var at: Int,
        var onHost: Marker?,
    ) : Marker {
        override val offset: Int
            get() = (onHost?.offset?.takeIf { it >= 0 } ?: at).coerceIn(0, document.length)

        override fun release() {
            dropHostMarker()
            markers -= this
        }

        fun dropHostMarker() {
            onHost?.release()
            onHost = null
        }
    }

    /** A [Marker] at the caret (the start of the selection), kept in step with every later change of the text. */
    fun markCaret(): Marker {
        val h = host
        val marker = TrackedMarker(selection.start, h?.markCaret())
        markers += marker
        return marker
    }

    fun attach(host: EditorHost) {
        this.host = host
        host.setAll(document.text(), pendingCaret.coerceIn(0, document.length))
        host.scrollToOffset(pendingScroll ?: pendingCaret)
        pendingScroll = null
        if (pendingFocus) {
            pendingFocus = false
            host.focusAndShowKeyboard()
        }
    }

    fun detach(host: EditorHost) {
        if (this.host === host) {
            markers.forEach { it.dropHostMarker() }
            pendingCaret = host.selection().start
            pendingScroll = host.firstVisibleOffset()
            this.host = null
        }
    }

    /** A live update or the first load: new text, history cleared, nothing recorded. */
    fun replaceAll(
        text: String,
        caret: Int = text.length,
    ) {
        val h = host
        // The scroll position stays on the same line number.
        val topLine = h?.let { document.lineOf(it.firstVisibleOffset().coerceIn(0, document.length)) }
        document = MarkdownDocument(text)
        // The view's markers go with its old text; the tracked offsets stay, clamped to the new text.
        markers.forEach {
            it.dropHostMarker()
            it.at = it.at.coerceIn(0, text.length)
        }
        history.clear()
        pendingCaret = caret.coerceIn(0, text.length)
        h?.setAll(text, pendingCaret)
        if (h != null && topLine != null) h.scrollToOffset(document.lineStart(topLine.coerceAtMost(document.lineCount - 1)))
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

    /** Source offset of the first visible line (or where the view will scroll to when it attaches). */
    fun firstVisibleOffset(): Int = host?.firstVisibleOffset() ?: pendingScroll ?: pendingCaret

    /** Scrolls the view, or the view that attaches next, so that the line holding [offset] is at the top. */
    fun scrollTo(offset: Int) {
        val at = offset.coerceIn(0, document.length)
        pendingScroll = at
        host?.scrollToOffset(at)
    }

    /** The caret goes to [offset], scrolled to the top, and the keyboard comes up, when the view attaches next. */
    fun placeCaret(offset: Int) {
        if (host != null) return
        val at = offset.coerceIn(0, document.length)
        pendingCaret = at
        pendingScroll = at
        requestFocus()
    }

    fun selectionChanged() = updatePressed()

    fun focusChanged(focused: Boolean) {
        _focused.value = focused
    }

    /** Focuses the view and raises the keyboard, now or when the view attaches (a New note opens ready to type). */
    fun requestFocus() {
        val h = host
        if (h == null) pendingFocus = true else h.focusAndShowKeyboard()
    }

    /** Applies [edit] as one undo step: to the view, or to the text alone while no view is attached. */
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

    /**
     * Runs a toolbar button. Returns false when it did nothing (no view yet). [ToolbarAction.Image] opens a sheet,
     * not an edit: the caller handles it, and here it does nothing.
     */
    fun toolbar(action: ToolbarAction): Boolean {
        if (host == null) return false
        val sel = selection
        val edit =
            when (action) {
                ToolbarAction.Image -> return false
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
        val h = host
        val removed = document.text().substring(edit.start, edit.end)
        val before = selection
        if (h != null) h.replaceSilently(edit.start, edit.end, edit.replacement, edit.selection) else pendingCaret = edit.selection.start
        committed(edit.start, removed, edit.replacement, before, edit.selection, record)
        h?.scrollToCaret()
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
        for (m in markers) m.at = shifted(m.at, start, start + removed.length, inserted.length)
        host?.restyle(lines)
        if (record) {
            history.record(HistoryStep(start, removed, inserted, selectionBefore, selectionAfter), clockMs())
            publishHistory()
        }
        updatePressed()
        _changes.tryEmit(Unit)
        _edits.tryEmit(Unit)
    }

    /** Where a point at [offset] goes when `[start, end)` becomes [inserted] characters (`SPAN_POINT_POINT` rules). */
    private fun shifted(
        offset: Int,
        start: Int,
        end: Int,
        inserted: Int,
    ): Int =
        when {
            offset < start -> offset
            offset >= end -> offset + inserted - (end - start)
            else -> start + inserted
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
