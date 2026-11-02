package app.citti.ui.markdown

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import app.citti.core.markdown.Selection

/**
 * The Edit mode view: an `EditText` without background that keeps [EditorState] informed of every change and styles
 * only the lines an edit touched. Typing is never handled here beyond that: the platform does the layout (it only
 * re-measures the edited paragraph, which is why this is not a Compose text field, see ADR 0009).
 */
internal class CittiEditText(
    context: Context,
    private val state: EditorState,
    var styles: SyntaxStyles,
) : EditText(context),
    EditorHost {
    /** True while the state or this view changes the text itself, so the watcher ignores it. */
    private var silent = false
    private var removedText = ""
    private var selectionBefore = Selection(0)

    init {
        background = null
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        isVerticalScrollBarEnabled = true
        setHorizontallyScrolling(false)
        addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {
                    if (silent) return
                    removedText = s.subSequence(start, start + count).toString()
                    selectionBefore = Selection(selectionStart.coerceAtLeast(0), selectionEnd.coerceAtLeast(0))
                    changeStart = start
                }

                override fun onTextChanged(
                    s: CharSequence,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {
                    if (silent) return
                    insertedText = s.subSequence(start, start + count).toString()
                }

                override fun afterTextChanged(s: Editable) {
                    if (silent) return
                    val start = changeStart
                    val removed = removedText
                    val inserted = insertedText
                    if (inserted == "\n") {
                        val edit = state.enterEdit(Selection(start, start + removed.length))
                        if (edit.replacement != "\n" || edit.start != start || edit.end != start + removed.length) {
                            // Put back what the keyboard replaced, then let the state apply the continuation as one step.
                            silently { s.replace(start, start + 1, removed) }
                            setSelection(start + removed.length)
                            state.apply(edit)
                            return
                        }
                    }
                    state.userChanged(start, removed, inserted, selectionBefore, Selection(selectionStart, selectionEnd))
                }
            },
        )
    }

    // A double tap switches mode (the screen decides); the second tap does not select a word.
    private var swallowing = false
    private val doubleTaps =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val handler = state.onDoubleTap ?: return false
                    swallowing = true
                    handler()
                    return true
                }
            },
        )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (state.onDoubleTap != null) {
            doubleTaps.onTouchEvent(event)
            if (swallowing) {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) swallowing = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private var changeStart = 0
    private var insertedText = ""

    override fun onSelectionChanged(
        selStart: Int,
        selEnd: Int,
    ) {
        super.onSelectionChanged(selStart, selEnd)
        // The TextView constructor calls this before our fields exist.
        @Suppress("SENSELESS_COMPARISON")
        if (state == null) return
        if (!silent) state.selectionChanged()
    }

    override fun onFocusChanged(
        focused: Boolean,
        direction: Int,
        previouslyFocusedRect: android.graphics.Rect?,
    ) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        @Suppress("SENSELESS_COMPARISON")
        if (state == null) return
        state.focusChanged(focused)
    }

    private inline fun silently(block: () -> Unit) {
        val was = silent
        silent = true
        try {
            block()
        } finally {
            silent = was
        }
    }

    // ---- EditorHost ----

    override fun text(): String = text.toString()

    override fun replaceSilently(
        start: Int,
        end: Int,
        replacement: String,
        selection: Selection,
    ) = silently {
        text.replace(start, end, replacement)
        setSelection(selection.start.coerceIn(0, text.length), selection.end.coerceIn(0, text.length))
    }

    override fun setAll(
        text: String,
        caret: Int,
    ) = silently {
        setText(text, BufferType.EDITABLE)
        setSelection(caret.coerceIn(0, text.length))
        winFrom = 0
        winTo = -1
        lineCountAtLastStyle = state.document.lineCount
        val line = state.document.lineOf(caret.coerceIn(0, text.length))
        moveWindow(line - MARGIN, line + MARGIN)
    }

    /**
     * Spans cost per span on every edit (the Editable shifts all spans after the change), so only the lines near the
     * viewport are styled: [winFrom]..[winTo], moved by [onScrollChanged]. Task 0b: styling all 9k lines made typing
     * in the middle of the note 100 % janky; a window keeps the span count bounded.
     */
    private var winFrom = 0
    private var winTo = -1

    private fun moveWindow(
        wantFrom: Int,
        wantTo: Int,
    ) {
        val doc = state.document
        val from = wantFrom.coerceAtLeast(0)
        val to = wantTo.coerceAtMost(doc.lineCount - 1)
        if (to < from) return
        silently {
            if (winTo < winFrom) {
                styleLines(from, to)
            } else {
                // Unstyle the lines that left the window, style the ones that entered.
                if (from > winFrom) unstyleLines(winFrom, minOf(from - 1, winTo))
                if (to < winTo) unstyleLines(maxOf(to + 1, winFrom), winTo)
                if (from < winFrom) styleLines(from, minOf(winFrom - 1, to))
                if (to > winTo) styleLines(maxOf(winTo + 1, from), to)
            }
        }
        winFrom = from
        winTo = to
    }

    override fun onScrollChanged(
        l: Int,
        t: Int,
        oldl: Int,
        oldt: Int,
    ) {
        super.onScrollChanged(l, t, oldl, oldt)
        @Suppress("SENSELESS_COMPARISON")
        if (state == null || silent) return
        val layout = layout ?: return
        val doc = state.document
        if (doc.lineCount == 0 || text.isEmpty()) return
        val top = doc.lineOf(layout.getLineStart(layout.getLineForVertical(scrollY)))
        val bottom = doc.lineOf(layout.getLineStart(layout.getLineForVertical(scrollY + height)))
        val nearTop = top < winFrom + EDGE && winFrom > 0
        val nearBottom = bottom > winTo - EDGE && winTo < doc.lineCount - 1
        if (nearTop || nearBottom || winTo < winFrom) {
            // While the text is moving, wait until it settles: restyling every frame costs more than plain text.
            removeCallbacks(settle)
            postDelayed(settle, SETTLE_MS)
        }
    }

    private val settle =
        Runnable {
            val layout = layout ?: return@Runnable
            val doc = state.document
            val top = doc.lineOf(layout.getLineStart(layout.getLineForVertical(scrollY)))
            val bottom = doc.lineOf(layout.getLineStart(layout.getLineForVertical(scrollY + height)))
            moveWindow(top - MARGIN, bottom + MARGIN)
        }

    private fun unstyleLines(
        first: Int,
        last: Int,
    ) {
        val doc = state.document
        val from = doc.lineStart(first)
        val to = (doc.lineEnd(last) + 1).coerceAtMost(text.length)
        for (old in text.getSpans(from, to, SyntaxSpan::class.java)) text.removeSpan(old)
    }

    private fun styleLines(
        first: Int,
        last: Int,
    ) {
        val doc = state.document
        val editable = text
        for (line in first..last) {
            val base = doc.lineStart(line)
            for (span in doc.lexedLine(line).spans) {
                val a = base + span.start
                val b = base + span.end
                if (a >= b || b > editable.length) continue
                for (style in styles.spansFor(span)) editable.setSpan(style, a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    override fun restyle(lines: IntRange) {
        val doc = state.document
        if (doc.lineCount == 0) return
        // The line count may have changed under the window: an edit inside it moves its end by the same amount.
        val delta = doc.lineCount - lineCountAtLastStyle
        if (lineCountAtLastStyle != 0 && delta != 0 && lines.first <= winTo) winTo = (winTo + delta).coerceAtLeast(winFrom)
        lineCountAtLastStyle = doc.lineCount
        val first = maxOf(lines.first, winFrom)
        val last = minOf(lines.last, winTo, doc.lineCount - 1)
        if (last < first) return
        silently {
            unstyleLines(first, last)
            styleLines(first, last)
        }
    }

    private var lineCountAtLastStyle = 0

    /** A zero-length `SPAN_POINT_POINT` span at the caret: the Editable moves it with every change around it. */
    override fun markCaret(): Marker {
        val span = CaretMark()
        val at = selectionStart.coerceIn(0, text.length)
        text.setSpan(span, at, at, Spanned.SPAN_POINT_POINT)
        return object : Marker {
            // -1 once the span is gone (the text was replaced): the state then uses its own offset.
            override val offset: Int get() = text.getSpanStart(span)

            override fun release() = text.removeSpan(span)
        }
    }

    /** The span object of [markCaret]; only its identity matters. */
    private class CaretMark

    override fun selection(): Selection = Selection(selectionStart.coerceAtLeast(0), selectionEnd.coerceAtLeast(0))

    override fun scrollToCaret() {
        val layout = layout ?: return
        val line = layout.getLineForOffset(selectionStart.coerceAtLeast(0))
        val rect = android.graphics.Rect(0, layout.getLineTop(line), 1, layout.getLineBottom(line))
        requestRectangleOnScreen(rect)
    }

    override fun firstVisibleOffset(): Int {
        val layout = layout ?: return 0
        return layout.getLineStart(layout.getLineForVertical(scrollY))
    }

    override fun scrollToOffset(offset: Int) {
        whenLaidOut {
            val layout = layout ?: return@whenLaidOut
            val line = layout.getLineForOffset(offset.coerceIn(0, text.length))
            val room = (layout.height + totalPaddingTop + totalPaddingBottom - height).coerceAtLeast(0)
            scrollTo(0, layout.getLineTop(line).coerceAtMost(room))
        }
    }

    // Posted: the view attaches before it is enabled and laid out, and the IME ignores a view not yet in a window.
    override fun focusAndShowKeyboard() {
        post {
            if (!requestFocus()) return@post
            context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
        clearFocus()
    }

    /** Runs [action] now, or after the first layout when the text has no [layout] yet. */
    private fun whenLaidOut(action: () -> Unit) {
        if (layout != null && height > 0) {
            action()
            return
        }
        addOnLayoutChangeListener(
            object : OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: android.view.View,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    oldLeft: Int,
                    oldTop: Int,
                    oldRight: Int,
                    oldBottom: Int,
                ) {
                    if (layout == null || height <= 0) return
                    removeOnLayoutChangeListener(this)
                    action()
                }
            },
        )
    }

    private companion object {
        const val MARGIN = 6
        const val EDGE = 3
        const val SETTLE_MS = 120L
    }
}
