package app.citti.core.markdown

/**
 * A note's text split into lines, each lexed with [LineLexer]. An edit re-lexes the lines it touches and then
 * only as many lines after them as it takes for the carried [LineState] to match the old one again (usually none;
 * typing a code fence runs to the end of the note, which is expected). Lines are separated by `\n` only; a `\r`
 * stays at the end of its line.
 *
 * Not thread safe: the editor owns one and calls it from the main thread.
 */
class MarkdownDocument(
    text: String = "",
) {
    private val lines = ArrayList<String>()
    private val lexed = ArrayList<LexedLine>()
    private var starts = IntArray(0)
    private var startsValid = false

    init {
        text.split('\n').forEach { lines += it }
        var state: LineState = LineLexer.start
        for (i in lines.indices) {
            val l = LineLexer.lexLine(lines[i], state, i)
            lexed += l
            state = l.endState
        }
    }

    val lineCount: Int get() = lines.size

    /** Length of the whole text. */
    val length: Int get() = lineStart(lines.size - 1) + lines.last().length

    fun text(): String = lines.joinToString("\n")

    fun lineText(i: Int): String = lines[i]

    fun lexedLine(i: Int): LexedLine = lexed[i]

    fun info(i: Int): LineInfo = lexed[i].info

    /** Offset of the first character of line [i]. */
    fun lineStart(i: Int): Int {
        ensureStarts()
        return starts[i]
    }

    /** Offset just after the last character of line [i], before its `\n`. */
    fun lineEnd(i: Int): Int = lineStart(i) + lines[i].length

    /** The line that contains [offset]; an offset at a `\n` belongs to the line it ends. */
    fun lineOf(offset: Int): Int {
        ensureStarts()
        var lo = 0
        var hi = lines.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** The state line [i] starts in. */
    fun stateAt(i: Int): LineState = if (i == 0) LineLexer.start else lexed[i - 1].endState

    /** Every lexed line, for comparing a document with a fresh one in tests. */
    fun snapshot(): List<LexedLine> = lexed.toList()

    /**
     * Replaces `[start, end)` with [replacement] and re-lexes. Returns the lines (in the new numbering) whose spans
     * must be applied again: from the first edited line to the last one that was re-lexed.
     */
    fun replace(
        start: Int,
        end: Int,
        replacement: String,
    ): IntRange {
        require(start in 0..end && end <= length) { "range $start..$end outside 0..$length" }
        val first = lineOf(start)
        val last = lineOf(end)
        val head = lines[first].substring(0, start - lineStart(first))
        val tail = lines[last].substring(end - lineStart(last))
        val pieces = (head + replacement + tail).split('\n')
        // The state the old lines after the edit started in; as long as the new lines reach it, nothing below changed.
        var oldCarry = lexed[last].endState

        for (k in last downTo first) {
            lines.removeAt(k)
            lexed.removeAt(k)
        }
        lines.addAll(first, pieces)
        lexed.addAll(first, pieces.map { PLACEHOLDER })
        startsValid = false

        var state = stateAt(first)
        for (k in pieces.indices) {
            val l = LineLexer.lexLine(lines[first + k], state, first + k)
            lexed[first + k] = l
            state = l.endState
        }
        var i = first + pieces.size
        var lastRelexed = i - 1
        while (i < lines.size && state != oldCarry) {
            val next = lexed[i].endState
            val l = LineLexer.lexLine(lines[i], state, i)
            lexed[i] = l
            state = l.endState
            oldCarry = next
            lastRelexed = i
            i++
        }
        return first..lastRelexed
    }

    private fun ensureStarts() {
        if (startsValid && starts.size == lines.size) return
        if (starts.size != lines.size) starts = IntArray(lines.size)
        var offset = 0
        for (i in lines.indices) {
            starts[i] = offset
            offset += lines[i].length + 1
        }
        startsValid = true
    }

    private companion object {
        val PLACEHOLDER = LexedLine(emptyList(), LineInfo(), LineLexer.start)
    }
}
