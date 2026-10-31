package app.citti.core.markdown

/** One undo step: at [start], [removed] was replaced by [inserted]. Selections are offsets before and after. */
data class HistoryStep(
    val start: Int,
    val removed: String,
    val inserted: String,
    val selectionBefore: Selection,
    val selectionAfter: Selection,
) {
    /** The edit that undoes this step. */
    fun undoEdit(): Edit = Edit(start, start + inserted.length, removed, selectionBefore)

    /** The edit that applies this step again. */
    fun redoEdit(): Edit = Edit(start, start + removed.length, inserted, selectionAfter)
}

/**
 * The undo history of one open Note. `EditText` has none, so the editor records every change here.
 *
 * Typing is coalesced: consecutive insertions of word characters at the end of the previous one form one step, as do
 * consecutive Backspaces. A step also ends at a whitespace character, after [pauseMs] without a change, and at any
 * change that is not that kind of typing (a toolbar action, list continuation, an autocomplete pick, a paste or a
 * replaced selection), which is its own step. A new change after an undo drops the redo steps.
 */
class EditorHistory(
    private val pauseMs: Long = 1_000,
    private val limit: Int = 500,
) {
    private val undoStack = ArrayDeque<HistoryStep>()
    private val redoStack = ArrayDeque<HistoryStep>()
    private var lastAt = 0L

    /** The last recorded step may take more typing: it was a typed word character or a Backspace. */
    private var open = false

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        open = false
    }

    /** Records a change the user made, coalescing typing. [nowMs] is any monotonic clock. */
    fun record(
        step: HistoryStep,
        nowMs: Long,
    ) {
        redoStack.clear()
        val last = undoStack.lastOrNull()
        if (last != null && open && nowMs - lastAt <= pauseMs) {
            val merged = merge(last, step)
            if (merged != null) {
                undoStack.removeLast()
                undoStack.addLast(merged)
                lastAt = nowMs
                open = continues(step)
                return
            }
        }
        undoStack.addLast(step)
        if (undoStack.size > limit) undoStack.removeFirst()
        lastAt = nowMs
        open = continues(step)
    }

    /** The step to undo, already moved to the redo stack; the caller applies [HistoryStep.undoEdit]. */
    fun undo(): HistoryStep? {
        val step = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(step)
        open = false
        return step
    }

    /** The step to redo, already moved back to the undo stack; the caller applies [HistoryStep.redoEdit]. */
    fun redo(): HistoryStep? {
        val step = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(step)
        open = false
        return step
    }

    private fun isTyped(step: HistoryStep) = step.removed.isEmpty() && step.inserted.length == 1 && !step.inserted[0].isWhitespace()

    private fun isBackspace(step: HistoryStep) = step.inserted.isEmpty() && step.removed.length == 1 && step.selectionBefore.isCaret

    private fun continues(step: HistoryStep) = isTyped(step) || isBackspace(step)

    private fun merge(
        last: HistoryStep,
        next: HistoryStep,
    ): HistoryStep? =
        when {
            isTyped(next) && last.removed.isEmpty() && next.start == last.start + last.inserted.length -> {
                last.copy(inserted = last.inserted + next.inserted, selectionAfter = next.selectionAfter)
            }

            // Backspace: the new deletion ends where the previous one began.
            isBackspace(next) && last.inserted.isEmpty() && next.start + next.removed.length == last.start -> {
                last.copy(start = next.start, removed = next.removed + last.removed, selectionAfter = next.selectionAfter)
            }

            else -> {
                null
            }
        }
}
