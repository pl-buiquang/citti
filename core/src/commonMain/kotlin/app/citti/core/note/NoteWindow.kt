package app.citti.core.note

/** The part of a Note a widget draws: [lines] numbered from [firstLine], and whether the Note goes on after them. */
data class NoteWindow(
    val lines: List<String>,
    val firstLine: Int,
    val more: Boolean,
)

object NoteWindows {
    const val MAX_LINES = 200
    const val MAX_CHARS = 10_000
    const val TAIL_LINES = 60

    /**
     * The window of [content] that starts at the Reading position [viewLine]: source lines split on `\n`, at most
     * [maxLines] lines and [maxChars] characters. With no position, or one at or past the last line, it is the last
     * [tail] lines (which never has `more`).
     */
    fun slice(
        content: String,
        viewLine: Int?,
        maxLines: Int = MAX_LINES,
        maxChars: Int = MAX_CHARS,
        tail: Int = TAIL_LINES,
    ): NoteWindow {
        val all = content.split('\n')
        val tailStart = maxOf(0, all.size - tail)
        val atTail = viewLine == null || viewLine < 0 || viewLine >= all.size - 1
        val start = if (atTail) tailStart else viewLine!!
        val taken = ArrayList<String>()
        var chars = 0
        var i = start
        while (i < all.size && taken.size < maxLines) {
            val line = all[i]
            if (taken.isNotEmpty() && chars + line.length > maxChars) break
            if (taken.isEmpty() && line.length > maxChars) {
                taken += line.take(maxChars)
                chars = maxChars
                i++
                break
            }
            taken += line
            chars += line.length
            i++
        }
        return NoteWindow(taken, start, more = !atTail && i < all.size)
    }
}
