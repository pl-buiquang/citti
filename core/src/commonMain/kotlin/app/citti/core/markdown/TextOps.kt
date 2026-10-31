package app.citti.core.markdown

/** A caret ([start] == [end]) or a selection. */
data class Selection(
    val start: Int,
    val end: Int = start,
) {
    val isCaret: Boolean get() = start == end
}

/** Replace `[start, end)` of the old text with [replacement], then select [selection] (offsets in the new text). */
data class Edit(
    val start: Int,
    val end: Int,
    val replacement: String,
    val selection: Selection,
)

enum class ToolbarAction { Bold, Italic, Heading, List, Checkbox, Link }

/**
 * The formatting actions of the toolbar and the Enter key, as pure functions of the document and the selection.
 * They only add or remove the marks of the action; no other text changes. The UI applies the [Edit] as one undo step.
 */
object TextOps {
    fun bold(
        doc: MarkdownDocument,
        sel: Selection,
    ): Edit = wrap(doc, sel, listOf("**"))

    fun italic(
        doc: MarkdownDocument,
        sel: Selection,
    ): Edit = wrap(doc, sel, listOf("_", "*"))

    /** [delims]: the first is what gets inserted; all of them are recognised when unwrapping. */
    private fun wrap(
        doc: MarkdownDocument,
        sel: Selection,
        delims: List<String>,
    ): Edit {
        val text = doc.text()
        val insert = delims.first()
        val bold = insert == "**"

        // A single * or _ is not the half of a double one.
        fun isSingle(
            d: String,
            at: Int,
        ) = bold || (text.getOrNull(at - 1) != d[0] && text.getOrNull(at + d.length) != d[0])

        // The range to work on: the selection, or the word around the caret.
        var s = sel.start
        var e = sel.end
        if (sel.isCaret) {
            val line = doc.lineOf(s)
            val from = doc.lineStart(line)
            val to = doc.lineEnd(line)
            while (s > from && text[s - 1].isLetterOrDigit()) s--
            while (e < to && text[e].isLetterOrDigit()) e++
        }
        for (d in delims) {
            val n = d.length
            // Delimiters just outside the range.
            if (s - n >= 0 && e + n <= text.length && text.startsWith(d, s - n) && text.startsWith(d, e) &&
                isSingle(d, s - n) && isSingle(d, e)
            ) {
                return Edit(s - n, e + n, text.substring(s, e), Selection(s - n, e - n))
            }
            // Delimiters inside the range.
            if (e - s >= 2 * n && text.startsWith(d, s) && text.startsWith(d, e - n) && isSingle(d, s) && isSingle(d, e - n)) {
                return Edit(s, e, text.substring(s + n, e - n), Selection(s, e - 2 * n))
            }
        }
        if (sel.isCaret) return Edit(sel.start, sel.start, insert + insert, Selection(sel.start + insert.length))
        return Edit(
            sel.start,
            sel.end,
            insert + text.substring(sel.start, sel.end) + insert,
            Selection(
                sel.start + insert.length,
                sel.end + insert.length,
            ),
        )
    }

    /** The lines a selection touches; a selection that ends at the very start of a line does not include it. */
    private fun linesOf(
        doc: MarkdownDocument,
        sel: Selection,
    ): IntRange {
        val first = doc.lineOf(sel.start)
        var last = doc.lineOf(sel.end)
        if (sel.end > sel.start && sel.end == doc.lineStart(last) && last > first) last--
        return first..last
    }

    /** One change inside a line: `[from, to)` (line-relative) becomes [with]. */
    private class LineChange(
        val line: Int,
        val from: Int,
        val to: Int,
        val with: String,
    )

    /** Applies [changes] (at most one per line, ascending) as one [Edit] from the first to the last changed line. */
    private fun edit(
        doc: MarkdownDocument,
        sel: Selection,
        changes: List<LineChange>,
    ): Edit {
        if (changes.isEmpty()) return Edit(sel.start, sel.start, "", sel)
        val firstLine = changes.first().line
        val lastLine = changes.last().line
        val base = doc.lineStart(firstLine)
        val sb = StringBuilder()
        for (l in firstLine..lastLine) {
            val text = doc.lineText(l)
            val c = changes.firstOrNull { it.line == l }
            if (c == null) sb.append(text) else sb.append(text, 0, c.from).append(c.with).append(text, c.to, text.length)
            if (l < lastLine) sb.append('\n')
        }

        fun map(offset: Int): Int {
            var delta = 0
            for (c in changes) {
                val at = doc.lineStart(c.line)
                val from = at + c.from
                val to = at + c.to
                if (offset >= to) {
                    delta += c.with.length - (c.to - c.from)
                } else if (offset > from) {
                    return from + delta + c.with.length
                }
            }
            return offset + delta
        }
        return Edit(base, doc.lineEnd(lastLine), sb.toString(), Selection(map(sel.start), map(sel.end)))
    }

    fun heading(
        doc: MarkdownDocument,
        sel: Selection,
    ): Edit {
        val changes = ArrayList<LineChange>()
        for (l in linesOf(doc, sel)) {
            val info = doc.info(l)
            if (info.verbatim) continue
            val text = doc.lineText(l)
            val level = info.headingLevel
            val next =
                if (level == 0) {
                    1
                } else if (level >= 3) {
                    0
                } else {
                    level + 1
                }
            val markStart = if (level == 0) info.indent.coerceAtMost(3) else text.indexOf('#').coerceAtLeast(0)
            val markEnd = if (level == 0) markStart else info.contentStart
            changes += LineChange(l, markStart, markEnd, if (next == 0) "" else "#".repeat(next) + " ")
        }
        return edit(doc, sel, changes)
    }

    fun list(
        doc: MarkdownDocument,
        sel: Selection,
    ): Edit {
        val lines = linesOf(doc, sel).filter { !doc.info(it).verbatim && doc.lineText(it).isNotBlank() }
        val allHave = lines.isNotEmpty() && lines.all { doc.info(it).marker != null }
        val changes = ArrayList<LineChange>()
        for (l in lines) {
            val info = doc.info(l)
            when {
                !allHave && info.marker == null -> changes += LineChange(l, info.indent, info.indent, "- ")
                allHave && info.task != null -> changes += LineChange(l, info.markerEnd, info.contentStart, "")
                allHave -> changes += LineChange(l, info.indent, info.markerEnd, "")
            }
        }
        return edit(doc, sel, changes)
    }

    fun checkbox(
        doc: MarkdownDocument,
        sel: Selection,
    ): Edit {
        val changes = ArrayList<LineChange>()
        for (l in linesOf(doc, sel)) {
            val info = doc.info(l)
            if (info.verbatim) continue
            when (info.task) {
                TaskState.Open -> {
                    changes += LineChange(l, info.markerEnd + 1, info.markerEnd + 2, "x")
                }

                TaskState.Done -> {
                    changes += LineChange(l, info.markerEnd + 1, info.markerEnd + 2, " ")
                }

                null -> {
                    if (info.marker != null) {
                        changes += LineChange(l, info.markerEnd, info.markerEnd, "[ ] ")
                    } else if (doc.lineText(l).isNotBlank()) {
                        changes += LineChange(l, info.indent, info.indent, "- [ ] ")
                    } else {
                        changes += LineChange(l, info.indent, info.indent, "- [ ] ")
                    }
                }
            }
        }
        return edit(doc, sel, changes)
    }

    /** `[[|selection]]` with the caret before the `|`, or `[[]]` with the caret inside. The caller opens autocomplete. */
    fun link(
        doc: MarkdownDocument,
        sel: Selection,
    ): Edit {
        if (sel.isCaret) return Edit(sel.start, sel.end, "[[]]", Selection(sel.start + 2))
        val s = doc.text().substring(sel.start, sel.end)
        return Edit(sel.start, sel.end, "[[|$s]]", Selection(sel.start + 2))
    }

    /**
     * What Enter does with the caret at [sel] (the text is not changed yet): continue a list or task item, end an
     * empty one, or insert a plain newline. A selection is replaced by a plain newline.
     */
    fun enter(
        doc: MarkdownDocument,
        sel: Selection,
    ): Edit {
        val plain = Edit(sel.start, sel.end, "\n", Selection(sel.start + 1))
        if (!sel.isCaret) return plain
        val line = doc.lineOf(sel.start)
        val info = doc.info(line)
        val marker = info.marker
        if (info.verbatim || marker == null) return plain
        val at = sel.start - doc.lineStart(line)
        if (at < info.contentStart) return plain
        val text = doc.lineText(line)
        if (text.substring(info.contentStart).isBlank() && at >= text.trimEnd().length) {
            // An empty item: remove its marker, keep the indent, no new line.
            val indent = text.substring(0, info.indent)
            val start = doc.lineStart(line)
            return Edit(start, doc.lineEnd(line), indent, Selection(start + indent.length))
        }
        val gap = text.substring(marker.length + info.indent, info.markerEnd).ifEmpty { " " }
        val next =
            if (marker[0].isDigit()) {
                (marker.dropLast(1).toLong() + 1).toString() + marker.last()
            } else {
                marker
            }
        val insert = "\n" + text.substring(0, info.indent) + next + gap + (if (info.task != null) "[ ] " else "")
        return Edit(sel.start, sel.end, insert, Selection(sel.start + insert.length))
    }

    /** The actions whose syntax is active at [sel] (the toolbar's pressed state). */
    fun pressed(
        doc: MarkdownDocument,
        sel: Selection,
    ): Set<ToolbarAction> {
        val line = doc.lineOf(sel.start)
        val info = doc.info(line)
        if (info.verbatim) return emptySet()
        val at = sel.start - doc.lineStart(line)
        val result = mutableSetOf<ToolbarAction>()
        val spans = doc.lexedLine(line).spans

        fun inside(vararg kinds: SpanKind) = spans.any { it.kind in kinds && at >= it.start && at <= it.end }
        if (inside(SpanKind.Bold)) result += ToolbarAction.Bold
        if (inside(SpanKind.Italic)) result += ToolbarAction.Italic
        if (info.headingLevel > 0) result += ToolbarAction.Heading
        if (info.marker != null) result += ToolbarAction.List
        if (info.task != null) result += ToolbarAction.Checkbox
        if (inside(SpanKind.WikilinkMark, SpanKind.WikilinkTarget, SpanKind.WikilinkAlias, SpanKind.LinkText, SpanKind.LinkTarget)) {
            result += ToolbarAction.Link
        }
        return result
    }
}

/** Applies an [Edit] to a plain string. */
fun String.apply(edit: Edit): String = substring(0, edit.start) + edit.replacement + substring(edit.end)
