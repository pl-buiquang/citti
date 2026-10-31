package app.citti.core.markdown

/** What a [Span] of a line is. The editor maps each kind to a style; a wrong guess only colors text. */
enum class SpanKind {
    HeadingMark,
    HeadingText,
    Bold,
    Italic,
    Strike,
    Highlight,
    Mark,
    InlineCode,
    CodeFence,
    CodeBlock,
    Frontmatter,
    ListMark,
    TaskMark,
    TaskDone,
    Quote,
    WikilinkMark,
    WikilinkTarget,
    WikilinkAlias,
    LinkText,
    LinkTarget,
    ImageMark,
    Url,
}

/**
 * [start, end) in the line, relative to its first character. [level] is the heading level for [SpanKind.HeadingText].
 * Spans of a line may overlap; apply them in list order, so a later one wins (`Bold` first, then its `Mark`s).
 */
data class Span(
    val start: Int,
    val end: Int,
    val kind: SpanKind,
    val level: Int = 0,
)

/** What a line starts in, carried from the previous line. */
sealed interface LineState {
    data object Normal : LineState

    data object InFrontmatter : LineState

    /** Inside a fenced code block opened by [length] times [char]. */
    data class InFence(
        val char: Char,
        val length: Int,
    ) : LineState
}

enum class TaskState { Open, Done }

/**
 * What the toolbar and Enter need to know about a line, so they do not re-parse it. Offsets are relative to the line.
 * [verbatim] lines (frontmatter, fenced code) have no markdown meaning: the other fields are empty there.
 */
data class LineInfo(
    val indent: Int = 0,
    /** The list marker as typed (`-`, `*`, `+`, `1.`, `2)`), or null. */
    val marker: String? = null,
    /** Offset just after the marker and the spaces that follow it; the indent end when there is no marker. */
    val markerEnd: Int = 0,
    val task: TaskState? = null,
    /** Offset where the item text starts: after the task box and its space, else [markerEnd]. */
    val contentStart: Int = 0,
    val headingLevel: Int = 0,
    val verbatim: Boolean = false,
)

data class LexedLine(
    val spans: List<Span>,
    val info: LineInfo,
    val endState: LineState,
)

/** Lines longer than this get block-level spans only: inline scanning stays cheap on huge pasted lines. */
private const val MAX_INLINE_LENGTH = 5_000
private const val MAX_DEPTH = 4

/** Lexes one line at a time. Pure and stateless: [LineState] is passed in and returned. */
object LineLexer {
    /** The state before the first line. */
    val start: LineState = LineState.Normal

    /** [index] is the line number: frontmatter can only open on line 0. */
    fun lexLine(
        line: String,
        state: LineState,
        index: Int,
    ): LexedLine {
        val end = if (line.endsWith('\r')) line.length - 1 else line.length
        val body = line.substring(0, end)
        return when (state) {
            LineState.InFrontmatter -> {
                val closes = body == "---" || body == "..."
                verbatim(end, SpanKind.Frontmatter, if (closes) LineState.Normal else state)
            }

            is LineState.InFence -> {
                if (closesFence(
                        body,
                        state,
                    )
                ) {
                    verbatim(end, SpanKind.CodeFence, LineState.Normal)
                } else {
                    verbatim(end, SpanKind.CodeBlock, state)
                }
            }

            LineState.Normal -> {
                if (index == 0 && body == "---") return verbatim(end, SpanKind.Frontmatter, LineState.InFrontmatter)
                val fence = opensFence(body)
                if (fence != null) verbatim(end, SpanKind.CodeFence, fence) else normal(body)
            }
        }
    }

    private fun verbatim(
        end: Int,
        kind: SpanKind,
        next: LineState,
    ) = LexedLine(if (end > 0) listOf(Span(0, end, kind)) else emptyList(), LineInfo(verbatim = true), next)

    private fun opensFence(body: String): LineState.InFence? {
        var i = 0
        while (i < body.length && i < 3 && body[i] == ' ') i++
        if (i >= body.length) return null
        val c = body[i]
        if (c != '`' && c != '~') return null
        var j = i
        while (j < body.length && body[j] == c) j++
        if (j - i < 3) return null
        if (c == '`' && body.indexOf('`', j) >= 0) return null
        return LineState.InFence(c, j - i)
    }

    private fun closesFence(
        body: String,
        fence: LineState.InFence,
    ): Boolean {
        var i = 0
        while (i < body.length && i < 3 && body[i] == ' ') i++
        var j = i
        while (j < body.length && body[j] == fence.char) j++
        return j - i >= fence.length && body.substring(j).isBlank()
    }

    private fun normal(body: String): LexedLine {
        val spans = ArrayList<Span>(4)
        var pos = 0
        var headingLevel = 0
        var indent = 0
        var marker: String? = null
        var markerEnd = 0
        var task: TaskState? = null
        var contentStart = 0

        // Block quote marks, possibly nested.
        while (true) {
            var p = pos
            while (p < body.length && p - pos < 3 && body[p] == ' ') p++
            if (p < body.length && body[p] == '>') {
                spans += Span(p, p + 1, SpanKind.Quote)
                pos = p + 1
                if (pos < body.length && body[pos] == ' ') pos++
            } else {
                break
            }
        }

        // ATX heading.
        var h = pos
        while (h < body.length && h - pos < 3 && body[h] == ' ') h++
        var hashes = 0
        while (h + hashes < body.length && body[h + hashes] == '#') hashes++
        val afterHashes = h + hashes
        if (hashes in 1..6 && (afterHashes == body.length || body[afterHashes] == ' ' || body[afterHashes] == '\t')) {
            headingLevel = hashes
            var textStart = afterHashes
            while (textStart < body.length && (body[textStart] == ' ' || body[textStart] == '\t')) textStart++
            spans += Span(h, afterHashes, SpanKind.HeadingMark, hashes)
            if (textStart < body.length) spans += Span(textStart, body.length, SpanKind.HeadingText, hashes)
            if (body.length <= MAX_INLINE_LENGTH) Inline(body, spans).run(textStart, body.length, 0)
            return LexedLine(spans, LineInfo(headingLevel = headingLevel, markerEnd = 0, contentStart = textStart), LineState.Normal)
        }

        // List item and task box.
        var p = pos
        while (p < body.length && (body[p] == ' ' || body[p] == '\t')) p++
        indent = p
        val m = listMarkerAt(body, p)
        if (m > p) {
            marker = body.substring(p, m)
            var e = m
            while (e < body.length && (body[e] == ' ' || body[e] == '\t')) e++
            markerEnd = e
            spans += Span(p, e, SpanKind.ListMark)
            contentStart = e
            if (e + 2 < body.length && body[e] == '[' && body[e + 2] == ']' && body[e + 1] in " xX" &&
                (e + 3 == body.length || body[e + 3] == ' ' || body[e + 3] == '\t')
            ) {
                task = if (body[e + 1] == ' ') TaskState.Open else TaskState.Done
                spans += Span(e, e + 3, SpanKind.TaskMark)
                var c = e + 3
                while (c < body.length && (body[c] == ' ' || body[c] == '\t')) c++
                contentStart = c
                if (task == TaskState.Done && c < body.length) spans += Span(c, body.length, SpanKind.TaskDone)
            }
        } else {
            indent = if (pos == 0) p else 0
            markerEnd = indent
            contentStart = indent
        }

        if (body.length <= MAX_INLINE_LENGTH) Inline(body, spans).run(contentStart.coerceAtLeast(pos), body.length, 0)
        return LexedLine(spans, LineInfo(indent, marker, markerEnd, task, contentStart, 0), LineState.Normal)
    }

    /** End offset of a list marker starting at [p] (followed by a space or the line end), or [p] when there is none. */
    private fun listMarkerAt(
        body: String,
        p: Int,
    ): Int {
        if (p >= body.length) return p
        val c = body[p]
        var e = p
        if (c == '-' || c == '*' || c == '+') {
            e = p + 1
        } else if (c in '0'..'9') {
            while (e < body.length && e - p < 9 && body[e] in '0'..'9') e++
            if (e < body.length && (body[e] == '.' || body[e] == ')')) e++ else return p
        } else {
            return p
        }
        return if (e == body.length || body[e] == ' ' || body[e] == '\t') e else p
    }

    /** Inline scanner over `[from, to)` of one line. */
    private class Inline(
        val s: String,
        val out: MutableList<Span>,
    ) {
        fun run(
            from: Int,
            to: Int,
            depth: Int,
        ) {
            var i = from
            while (i < to) {
                i =
                    when (val c = s[i]) {
                        '\\' -> {
                            i + 2
                        }

                        '`' -> {
                            code(i, to)
                        }

                        '!' -> {
                            if (s.startsWith("![[", i)) {
                                wikilink(i, i + 1, to)
                            } else if (i + 1 < to &&
                                s[i + 1] == '['
                            ) {
                                image(i, to)
                            } else {
                                i + 1
                            }
                        }

                        '[' -> {
                            if (s.startsWith("[[", i)) wikilink(i, i, to) else link(i, to, depth)
                        }

                        '*', '_' -> {
                            emphasis(i, to, c, depth)
                        }

                        '~', '=' -> {
                            if (s.startsWith("$c$c", i)) delimited(i, to, c, depth) else i + 1
                        }

                        'h' -> {
                            url(i, from, to)
                        }

                        else -> {
                            i + 1
                        }
                    }
            }
        }

        private fun code(
            i: Int,
            to: Int,
        ): Int {
            var n = 0
            while (i + n < to && s[i + n] == '`') n++
            var j = i + n
            while (j < to) {
                if (s[j] == '`') {
                    var m = 0
                    while (j + m < to && s[j + m] == '`') m++
                    if (m == n) {
                        out += Span(i, j + m, SpanKind.InlineCode)
                        return j + m
                    }
                    j += m
                } else {
                    j++
                }
            }
            return i + n
        }

        /** `[[target|alias]]` at [b], with `!` at [i] for an embed (else [i] == [b]). */
        private fun wikilink(
            i: Int,
            b: Int,
            to: Int,
        ): Int {
            val close = s.indexOf("]]", b + 2)
            if (close < 0 || close + 2 > to) return i + 1
            val pipe = s.indexOf('|', b + 2).takeIf { it in 0 until close }
            val targetEnd = pipe ?: close
            if (i != b) out += Span(i, b, SpanKind.ImageMark)
            out += Span(b, b + 2, SpanKind.WikilinkMark)
            if (targetEnd > b + 2) out += Span(b + 2, targetEnd, SpanKind.WikilinkTarget)
            if (pipe != null) {
                out += Span(pipe, pipe + 1, SpanKind.Mark)
                if (close > pipe + 1) out += Span(pipe + 1, close, SpanKind.WikilinkAlias)
            }
            out += Span(close, close + 2, SpanKind.WikilinkMark)
            return close + 2
        }

        /** End of the `(…)` that starts at [open] (balanced), or -1. */
        private fun closingParen(
            open: Int,
            to: Int,
        ): Int {
            var depth = 0
            for (k in open until to) {
                when (s[k]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) return k
                    ' ' -> if (depth == 1 && !s.startsWith(" \"", k)) return -1
                }
            }
            return -1
        }

        private fun image(
            i: Int,
            to: Int,
        ): Int {
            val cb = s.indexOf(']', i + 2)
            if (cb < 0 || cb + 1 >= to || s[cb + 1] != '(') return i + 1
            val cp = closingParen(cb + 1, to)
            if (cp < 0) return i + 1
            out += Span(i, cp + 1, SpanKind.ImageMark)
            return cp + 1
        }

        private fun link(
            i: Int,
            to: Int,
            depth: Int,
        ): Int {
            val cb = s.indexOf(']', i + 1)
            if (cb < 0 || cb + 1 >= to || s[cb + 1] != '(') return i + 1
            val cp = closingParen(cb + 1, to)
            if (cp < 0) return i + 1
            if (cb > i + 1) out += Span(i + 1, cb, SpanKind.LinkText)
            out += Span(i, i + 1, SpanKind.Mark)
            out += Span(cb, cb + 2, SpanKind.Mark)
            if (cp > cb + 2) out += Span(cb + 2, cp, SpanKind.LinkTarget)
            out += Span(cp, cp + 1, SpanKind.Mark)
            return cp + 1
        }

        private fun isWord(k: Int) = k >= 0 && k < s.length && s[k].isLetterOrDigit()

        /** Start of the next [len]-long run of [c] in `[from, to)`, skipping code spans and escapes; -1 when none. */
        private fun findClose(
            from: Int,
            to: Int,
            c: Char,
            len: Int,
        ): Int {
            var k = from
            while (k < to) {
                val ch = s[k]
                if (ch == '\\') {
                    k += 2
                } else if (ch == '`') {
                    var n = 0
                    while (k + n < to && s[k + n] == '`') n++
                    val close = s.indexOf("`".repeat(n), k + n)
                    k = if (close in 0 until to) close + n else k + n
                } else if (ch == c) {
                    var r = 0
                    while (k + r < to && s[k + r] == c) r++
                    if (r == len) return k
                    k += r
                } else {
                    k++
                }
            }
            return -1
        }

        private fun emphasis(
            i: Int,
            to: Int,
            c: Char,
            depth: Int,
        ): Int {
            var r = 0
            while (i + r < to && s[i + r] == c) r++
            val nextIsSpace = i + r >= to || s[i + r].isWhitespace()
            if (nextIsSpace) return i + r
            if (r >= 2) {
                if (c == '_' && isWord(i - 1)) return i + r
                val k = findClose(i + 2, to, c, 2)
                if (k < i + 3 || s[k - 1].isWhitespace() || (c == '_' && isWord(k + 2))) return i + r
                out += Span(i, k + 2, SpanKind.Bold)
                out += Span(i, i + 2, SpanKind.Mark)
                out += Span(k, k + 2, SpanKind.Mark)
                if (depth < MAX_DEPTH) run(i + 2, k, depth + 1)
                return k + 2
            }
            // One mark: not inside a word, so snake_case_word and 2*3*4 stay plain.
            if (isWord(i - 1)) return i + 1
            val k = findClose(i + 1, to, c, 1)
            if (k < i + 2 || s[k - 1].isWhitespace() || isWord(k + 1)) return i + 1
            out += Span(i, k + 1, SpanKind.Italic)
            out += Span(i, i + 1, SpanKind.Mark)
            out += Span(k, k + 1, SpanKind.Mark)
            if (depth < MAX_DEPTH) run(i + 1, k, depth + 1)
            return k + 1
        }

        /** `~~strike~~` and `==highlight==`. */
        private fun delimited(
            i: Int,
            to: Int,
            c: Char,
            depth: Int,
        ): Int {
            val k = findClose(i + 2, to, c, 2)
            if (k < i + 3 || s[i + 2].isWhitespace() || s[k - 1].isWhitespace()) return i + 2
            out += Span(i, k + 2, if (c == '~') SpanKind.Strike else SpanKind.Highlight)
            out += Span(i, i + 2, SpanKind.Mark)
            out += Span(k, k + 2, SpanKind.Mark)
            if (depth < MAX_DEPTH) run(i + 2, k, depth + 1)
            return k + 2
        }

        private fun url(
            i: Int,
            from: Int,
            to: Int,
        ): Int {
            val scheme =
                when {
                    s.startsWith("https://", i) -> 8
                    s.startsWith("http://", i) -> 7
                    else -> return i + 1
                }
            if (i > from && s[i - 1].isLetterOrDigit()) return i + 1
            var e = i + scheme
            while (e < to && !s[e].isWhitespace() && s[e] != '<' && s[e] != '>' && s[e] != '"') e++
            while (e > i + scheme && s[e - 1] in ".,;:!?)]'") e--
            if (e == i + scheme) return i + scheme
            out += Span(i, e, SpanKind.Url)
            return e
        }
    }
}
