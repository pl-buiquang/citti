package app.citti.core.markdown

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/** A long code block or paragraph is cut into chunks of at most this many lines, so a lazy list stays smooth. */
const val CHUNK_LINES = 40

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "avif", "heic")
private val OBSIDIAN_INLINE = Regex("""(!?)\[\[([^\[\]\n]*)\]\]|==([^=\n][^\n]*?)==""")
private val SIZE = Regex("""^(\d+)(?:x(\d+))?$""")
private val ESCAPE = Regex("""\\([!-/:-@\[-`{-~])""")

/**
 * Parses a Note into the Preview model: GFM through `org.jetbrains:markdown`, plus Obsidian's wikilinks, embeds,
 * `|width`, `==highlight==` and frontmatter. Pure; run it off the main thread. The text is never changed: every
 * block carries its source range.
 */
object ObsidianMarkdown {
    fun parse(text: String): Doc {
        val blocks = ArrayList<Block>()
        val bodyStart = frontmatterEnd(text)
        if (bodyStart > 0) blocks += properties(text, bodyStart)
        // Wikilinks are hidden from the CommonMark parser (same length, so offsets hold): `[[a|b]]` would otherwise
        // become a link reference, and its `|` a table cell.
        val masked = mask(text)
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(masked.substring(bodyStart))
        Converter(text, bodyStart).blocks(tree.children, 0, blocks)
        return Doc(blocks)
    }

    // ---- frontmatter ----

    /** Offset just after the closing line of the frontmatter, or 0 when there is none (or it is never closed). */
    private fun frontmatterEnd(text: String): Int {
        val first = text.indexOf('\n')
        if (first < 0 || text.substring(0, first).trimEnd('\r') != "---") return 0
        var pos = first + 1
        while (pos <= text.length) {
            val nl = text.indexOf('\n', pos)
            val lineEnd = if (nl < 0) text.length else nl
            val line = text.substring(pos, lineEnd).trimEnd('\r')
            if (line == "---" || line == "...") return if (nl < 0) text.length else nl + 1
            if (nl < 0) return 0
            pos = nl + 1
        }
        return 0
    }

    private fun properties(
        text: String,
        end: Int,
    ): Block.Properties {
        val lines =
            text
                .substring(0, end)
                .trimEnd('\n')
                .split('\n')
                .map { it.trimEnd('\r') }
        val inner = lines.drop(1).dropLast(1)
        val entries = ArrayList<Property>()
        var ok = true
        for (line in inner) {
            if (line.isBlank()) continue
            val item = Regex("""^\s+-\s+(.*)$|^-\s+(.*)$""").matchEntire(line)
            if (item != null && entries.isNotEmpty()) {
                val last = entries.removeAt(entries.size - 1)
                entries += last.copy(values = last.values + unquote(item.groupValues[1].ifEmpty { item.groupValues[2] }))
                continue
            }
            val kv = Regex("""^([^\s:#][^:]*):(?:\s+(.*))?$""").matchEntire(line)
            if (kv == null) {
                ok = false
                break
            }
            val value = kv.groupValues[2].trim()
            val values =
                when {
                    value.isEmpty() -> {
                        emptyList()
                    }

                    value.startsWith("[") && value.endsWith("]") -> {
                        value
                            .substring(1, value.length - 1)
                            .split(',')
                            .map {
                                unquote(it.trim())
                            }.filter { it.isNotEmpty() }
                    }

                    else -> {
                        listOf(unquote(value))
                    }
                }
            entries += Property(kv.groupValues[1].trim(), values)
        }
        return Block.Properties(0, end, if (ok) entries else null, inner.joinToString("\n"))
    }

    private fun unquote(s: String): String =
        if (s.length >= 2 && (s[0] == '"' || s[0] == '\'') &&
            s.last() == s[0]
        ) {
            s.substring(1, s.length - 1)
        } else {
            s
        }

    // ---- masking ----

    /** [text] with every wikilink (and its `!`) replaced by `x` characters. Code is left alone: the lexer skips it. */
    private fun mask(text: String): String {
        if (!text.contains("[[")) return text
        val doc = MarkdownDocument(text)
        val chars = text.toCharArray()
        for (line in 0 until doc.lineCount) {
            val spans = doc.lexedLine(line).spans
            if (spans.none { it.kind == SpanKind.WikilinkMark }) continue
            val base = doc.lineStart(line)
            var open = -1
            for ((k, span) in spans.withIndex()) {
                if (span.kind != SpanKind.WikilinkMark) continue
                if (open < 0) {
                    val bang = k > 0 && spans[k - 1].kind == SpanKind.ImageMark && spans[k - 1].end == span.start
                    open = if (bang) spans[k - 1].start else span.start
                } else {
                    for (p in open until span.end) chars[base + p] = 'x'
                    open = -1
                }
            }
        }
        return String(chars)
    }

    // ---- AST to blocks ----

    private class Converter(
        val src: String,
        val base: Int,
    ) {
        fun ASTNode.s() = startOffset + base

        fun ASTNode.e() = endOffset + base

        fun ASTNode.raw() = src.substring(s(), e())

        fun blocks(
            nodes: List<ASTNode>,
            depth: Int,
            out: MutableList<Block>,
        ) {
            for (n in nodes) block(n, depth, out)
        }

        fun block(
            n: ASTNode,
            depth: Int,
            out: MutableList<Block>,
        ) {
            when (n.type) {
                MarkdownTokenTypes.EOL, MarkdownTokenTypes.WHITE_SPACE, MarkdownTokenTypes.BLOCK_QUOTE -> {
                    Unit
                }

                MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3,
                MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6,
                -> {
                    val content = n.children.firstOrNull { it.type == MarkdownTokenTypes.ATX_CONTENT }
                    out += Block.Heading(n.s(), n.e(), levelOf(n.type), content?.let { inlines(it.children) }.orEmpty().trimEdges())
                }

                MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2 -> {
                    val content = n.children.firstOrNull { it.type == MarkdownTokenTypes.SETEXT_CONTENT }
                    out +=
                        Block.Heading(
                            n.s(),
                            n.e(),
                            if (n.type ==
                                MarkdownElementTypes.SETEXT_1
                            ) {
                                1
                            } else {
                                2
                            },
                            content?.let { inlines(it.children) }.orEmpty().trimEdges(),
                        )
                }

                MarkdownElementTypes.PARAGRAPH -> {
                    paragraph(n, depth, out)
                }

                MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST -> {
                    list(n, depth, out)
                }

                MarkdownElementTypes.BLOCK_QUOTE -> {
                    val inner = ArrayList<Block>()
                    blocks(n.children, depth, inner)
                    out += Block.Quote(n.s(), n.e(), inner)
                }

                MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK -> {
                    code(n, depth, out)
                }

                GFMElementTypes.TABLE -> {
                    table(n, out)
                }

                MarkdownTokenTypes.HORIZONTAL_RULE -> {
                    out += Block.Rule(n.s(), n.e())
                }

                else -> {
                    // HTML blocks, link definitions and anything unknown: shown as the literal source.
                    val raw = n.raw()
                    if (raw.isNotBlank()) out += Block.Paragraph(n.s(), n.e(), lines(raw), depth)
                }
            }
        }

        private fun levelOf(type: IElementType) =
            when (type) {
                MarkdownElementTypes.ATX_1 -> 1
                MarkdownElementTypes.ATX_2 -> 2
                MarkdownElementTypes.ATX_3 -> 3
                MarkdownElementTypes.ATX_4 -> 4
                MarkdownElementTypes.ATX_5 -> 5
                else -> 6
            }

        /** Literal text with line breaks. */
        private fun lines(raw: String): List<Inline> =
            raw.trimEnd('\n').split('\n').flatMapIndexed { i, l ->
                if (i ==
                    0
                ) {
                    listOf(Inline.Text(l))
                } else {
                    listOf(Inline.LineBreak, Inline.Text(l))
                }
            }

        private fun paragraph(
            n: ASTNode,
            depth: Int,
            out: MutableList<Block>,
        ) {
            val inl = inlines(n.children).trimEdges()
            val images = inl.filter { it !is Inline.LineBreak && !(it is Inline.Text && it.text.isBlank()) }
            if (images.isNotEmpty() && images.all { it is Inline.Image }) {
                out += Block.ImageBlock(n.s(), n.e(), images.filterIsInstance<Inline.Image>(), depth)
                return
            }
            // Long paragraphs: cut at line breaks, with the source range of each chunk.
            val breaks = inl.count { it == Inline.LineBreak }
            if (breaks < CHUNK_LINES) {
                out += Block.Paragraph(n.s(), n.e(), inl, depth)
                return
            }
            val raw = n.raw()
            val lineStarts =
                ArrayList<Int>().apply {
                    add(0)
                    raw.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
                }
            var from = 0
            var seen = 0
            val chunk = ArrayList<Inline>()
            var chunkStartLine = 0
            var line = 0
            for (item in inl) {
                if (item == Inline.LineBreak) {
                    line++
                    seen++
                    if (seen == CHUNK_LINES) {
                        val endOffset = n.s() + lineStarts[line] - 1
                        out += Block.Paragraph(n.s() + lineStarts[chunkStartLine], endOffset, chunk.toList(), depth)
                        chunk.clear()
                        chunkStartLine = line
                        seen = 0
                        from = line
                        continue
                    }
                }
                chunk += item
            }
            out += Block.Paragraph(n.s() + lineStarts[from.coerceAtMost(lineStarts.size - 1)], n.e(), chunk.toList(), depth)
        }

        private fun list(
            n: ASTNode,
            depth: Int,
            out: MutableList<Block>,
        ) {
            val ordered = n.type == MarkdownElementTypes.ORDERED_LIST
            for (item in n.children) {
                if (item.type != MarkdownElementTypes.LIST_ITEM) continue
                var number: Int? = null
                var task: Task? = null
                var first: ASTNode? = null
                val rest = ArrayList<ASTNode>()
                for (c in item.children) {
                    when {
                        c.type == MarkdownTokenTypes.LIST_BULLET -> Unit
                        c.type == MarkdownTokenTypes.LIST_NUMBER -> number = c.raw().takeWhile { it.isDigit() }.toIntOrNull()
                        c.type == GFMTokenTypes.CHECK_BOX -> task = Task(c.raw().getOrNull(1).let { it == 'x' || it == 'X' }, c.s())
                        c.type == MarkdownElementTypes.PARAGRAPH && first == null -> first = c
                        else -> rest += c
                    }
                }
                val end =
                    first?.e()
                        ?: item.children
                            .lastOrNull {
                                it.type != MarkdownTokenTypes.EOL && it.type != MarkdownTokenTypes.WHITE_SPACE &&
                                    it !in rest
                            }?.e()
                        ?: item.e()
                val inl = first?.let { inlines(it.children).trimEdges() }.orEmpty()
                out += Block.ListItem(item.s(), end, depth, ordered, number, task, inl)
                for (c in rest) {
                    if (c.type == MarkdownElementTypes.UNORDERED_LIST ||
                        c.type == MarkdownElementTypes.ORDERED_LIST
                    ) {
                        list(c, depth + 1, out)
                    } else {
                        block(c, depth + 1, out)
                    }
                }
            }
        }

        private fun code(
            n: ASTNode,
            depth: Int,
            out: MutableList<Block>,
        ) {
            val raw = n.raw()
            val rows = raw.split('\n')
            val isFence = n.type == MarkdownElementTypes.CODE_FENCE
            val info =
                if (isFence) {
                    rows
                        .first()
                        .trim()
                        .trimStart('`', '~')
                        .trim()
                        .ifEmpty { null }
                } else {
                    null
                }
            // The fence lines are not part of the text; an indented block has none.
            val bodyFrom = if (isFence) 1 else 0
            val closes = isFence && rows.size > 1 && n.children.any { it.type == MarkdownTokenTypes.CODE_FENCE_END }
            val bodyTo = if (closes) rows.size - 1 else rows.size
            val body = rows.subList(bodyFrom.coerceAtMost(rows.size), bodyTo.coerceAtLeast(bodyFrom.coerceAtMost(rows.size)))
            val starts =
                ArrayList<Int>().apply {
                    var o = 0
                    rows.forEach {
                        add(o)
                        o += it.length + 1
                    }
                }
            if (body.size <= CHUNK_LINES) {
                out +=
                    Block.Code(
                        n.s(),
                        n.e(),
                        info,
                        body.joinToString("\n") { if (isFence) it else it.removePrefix("    ").removePrefix("\t") },
                        depth,
                    )
                return
            }
            var i = 0
            while (i < body.size) {
                val j = minOf(i + CHUNK_LINES, body.size)
                val firstRow = bodyFrom + i
                val start = if (i == 0) n.s() else n.s() + starts[firstRow]
                val end = if (j == body.size) n.e() else n.s() + starts[bodyFrom + j] - 1
                out += Block.Code(start, end, info, body.subList(i, j).joinToString("\n"), depth, continued = i > 0)
                i = j
            }
        }

        private fun table(
            n: ASTNode,
            out: MutableList<Block>,
        ) {
            fun cells(row: ASTNode) = row.children.filter { it.type == GFMTokenTypes.CELL }.map { inlines(it.children).trimEdges() }
            val header =
                n.children
                    .firstOrNull { it.type == GFMElementTypes.HEADER }
                    ?.let(::cells)
                    .orEmpty()
            val rows = n.children.filter { it.type == GFMElementTypes.ROW }.map(::cells)
            val sep = n.children.firstOrNull { it.type == GFMTokenTypes.TABLE_SEPARATOR && it.endOffset - it.startOffset > 1 }
            val align =
                sep
                    ?.raw()
                    ?.trim()
                    ?.trim('|')
                    ?.split('|')
                    ?.map {
                        val c = it.trim()
                        when {
                            c.startsWith(":") && c.endsWith(":") -> Align.Center
                            c.startsWith(":") -> Align.Left
                            c.endsWith(":") -> Align.Right
                            else -> Align.None
                        }
                    }.orEmpty()
            out += Block.Table(n.s(), n.e(), header, rows, align)
        }

        // ---- inlines ----

        fun inlines(nodes: List<ASTNode>): List<Inline> {
            val out = ArrayList<Inline>()
            val buf = StringBuilder()

            fun flush() {
                if (buf.isNotEmpty()) {
                    obsidian(buf.toString(), out)
                    buf.setLength(0)
                }
            }
            for (n in nodes) {
                val t = n.type
                when {
                    t == MarkdownTokenTypes.EOL -> {
                        flush()
                        out += Inline.LineBreak
                    }

                    t == MarkdownElementTypes.STRONG -> {
                        flush()
                        out += Inline.Bold(inlines(n.children.filter { it.type != MarkdownTokenTypes.EMPH || it.children.isNotEmpty() }))
                    }

                    t == MarkdownElementTypes.EMPH && n.children.isNotEmpty() -> {
                        flush()
                        out += Inline.Italic(inlines(n.children.filter { !(it.type == MarkdownTokenTypes.EMPH && it.children.isEmpty()) }))
                    }

                    t == GFMElementTypes.STRIKETHROUGH -> {
                        flush()
                        out += Inline.Strike(inlines(n.children.filter { it.type != GFMTokenTypes.TILDE }))
                    }

                    t == MarkdownElementTypes.CODE_SPAN -> {
                        flush()
                        out +=
                            Inline.Code(
                                n.children
                                    .filter { it.type != MarkdownTokenTypes.BACKTICK }
                                    .joinToString("") { it.raw() }
                                    .trim(),
                            )
                    }

                    t == MarkdownElementTypes.INLINE_LINK -> {
                        flush()
                        out += link(n)
                    }

                    t == MarkdownElementTypes.IMAGE -> {
                        flush()
                        out += image(n)
                    }

                    t == GFMTokenTypes.GFM_AUTOLINK || t == MarkdownElementTypes.AUTOLINK -> {
                        flush()
                        val url = n.raw().trim('<', '>')
                        out += Inline.Link(url, listOf(Inline.Text(url)))
                    }

                    else -> {
                        buf.append(n.raw())
                    }
                }
            }
            flush()
            return out
        }

        private fun linkText(n: ASTNode): List<Inline> {
            val text = n.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT } ?: return emptyList()
            return inlines(text.children.filter { it.type != MarkdownTokenTypes.LBRACKET && it.type != MarkdownTokenTypes.RBRACKET })
        }

        private fun destination(n: ASTNode): String =
            n.children
                .firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
                ?.raw()
                ?.trim()
                ?.removeSurrounding(
                    "<",
                    ">",
                ).orEmpty()

        private fun link(n: ASTNode): Inline {
            val url = destination(n)
            val children = linkText(n).ifEmpty { listOf(Inline.Text(url)) }
            val path = url.substringBefore('#').substringBefore('?')
            val web = url.contains("://") || url.startsWith("mailto:") || url.startsWith("tel:")
            return if (!web && path.endsWith(".md", ignoreCase = true)) Inline.NoteLink(url, children) else Inline.Link(url, children)
        }

        private fun image(n: ASTNode): Inline {
            val inner = n.children.firstOrNull { it.type == MarkdownElementTypes.INLINE_LINK } ?: return Inline.Text(n.raw())
            val src = destination(inner)
            val altRaw =
                inner.children
                    .firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                    ?.raw()
                    ?.removeSurrounding(
                        "[",
                        "]",
                    ).orEmpty()
            val pipe = altRaw.lastIndexOf('|')
            val size = if (pipe >= 0) SIZE.matchEntire(altRaw.substring(pipe + 1).trim()) else SIZE.matchEntire(altRaw.trim())
            val alt =
                if (size != null && pipe >= 0) {
                    altRaw.substring(0, pipe)
                } else if (size != null) {
                    ""
                } else {
                    altRaw
                }
            return Inline.Image(src, alt.trim(), size?.groupValues?.get(1)?.toIntOrNull(), size?.groupValues?.get(2)?.toIntOrNull())
        }

        /** Wikilinks, embeds and `==highlight==` in a run of plain text. */
        private fun obsidian(
            text: String,
            out: MutableList<Inline>,
        ) {
            var last = 0
            for (m in OBSIDIAN_INLINE.findAll(text)) {
                if (m.range.first > last) out += Inline.Text(unescape(text.substring(last, m.range.first)))
                last = m.range.last + 1
                val highlight = m.groups[3]
                if (highlight != null) {
                    out += Inline.Highlight(listOf(Inline.Text(highlight.value)))
                    continue
                }
                val embed = m.groupValues[1].isNotEmpty()
                val inner = m.groupValues[2]
                val pipe = inner.indexOf('|')
                val target = (if (pipe >= 0) inner.substring(0, pipe) else inner).trim()
                val alias = if (pipe >= 0) inner.substring(pipe + 1).trim().ifEmpty { null } else null
                if (!embed) {
                    out += Inline.Wikilink(target, alias)
                } else if (target.substringBeforeLast('#').substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS) {
                    val size = alias?.let { SIZE.matchEntire(it) }
                    out +=
                        Inline.Image(
                            target,
                            if (size ==
                                null
                            ) {
                                alias ?: target.substringAfterLast('/')
                            } else {
                                target.substringAfterLast('/')
                            },
                            size?.groupValues?.get(1)?.toIntOrNull(),
                            size?.groupValues?.get(2)?.toIntOrNull(),
                        )
                } else {
                    out += Inline.Embed(target)
                }
            }
            if (last < text.length) out += Inline.Text(unescape(text.substring(last)))
        }

        private fun unescape(s: String) = if (s.indexOf('\\') < 0) s else ESCAPE.replace(s) { it.groupValues[1] }

        private fun Inline.isBlankRun() = this == Inline.LineBreak || (this is Inline.Text && text.isBlank())

        private fun List<Inline>.trimEdges(): List<Inline> {
            var from = 0
            var to = size
            while (from < to && this[from].isBlankRun()) from++
            while (to > from && this[to - 1].isBlankRun()) to--
            val r = subList(from, to).toMutableList()
            (r.firstOrNull() as? Inline.Text)?.let { r[0] = it.copy(text = it.text.trimStart()) }
            (r.lastOrNull() as? Inline.Text)?.let { r[r.size - 1] = it.copy(text = it.text.trimEnd()) }
            return r
        }
    }
}
