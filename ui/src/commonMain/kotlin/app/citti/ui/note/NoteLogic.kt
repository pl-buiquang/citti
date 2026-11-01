package app.citti.ui.note

import app.citti.core.markdown.Block
import app.citti.core.markdown.Doc
import app.citti.core.markdown.Inline
import app.citti.core.markdown.Task
import app.citti.core.sync.SyncStatus

/** What the save indicator under the title says. */
enum class SaveIndicator { Saved, Pending, Syncing, Offline, Failed }

/**
 * [pending]: the Note has text the server does not have yet (typed, or saved locally and not pushed). [status] is the
 * app's sync status. Auth failure keeps "Pending": the banner says what to do.
 */
fun saveIndicator(
    pending: Boolean,
    status: SyncStatus?,
): SaveIndicator =
    when {
        !pending -> SaveIndicator.Saved
        status is SyncStatus.Running -> SaveIndicator.Syncing
        status is SyncStatus.Offline -> SaveIndicator.Offline
        status is SyncStatus.ServerError || status is SyncStatus.Failed -> SaveIndicator.Failed
        else -> SaveIndicator.Pending
    }

/** A link of the Preview model, as the resolver sees it. */
sealed interface LinkRef {
    /** `[[target]]`, [target] as written. */
    data class Wiki(
        val target: String,
    ) : LinkRef

    /** A markdown link to a `.md` file, [url] as written. */
    data class Markdown(
        val url: String,
    ) : LinkRef
}

/** The links of [this] that point at a Note, once each, in reading order. */
fun Doc.linkRefs(): Set<LinkRef> {
    val refs = LinkedHashSet<LinkRef>()

    fun inlines(list: List<Inline>) {
        for (inline in list) {
            when (inline) {
                is Inline.Wikilink -> refs += LinkRef.Wiki(inline.target)
                is Inline.NoteLink -> refs += LinkRef.Markdown(inline.path)
                is Inline.Bold -> inlines(inline.children)
                is Inline.Italic -> inlines(inline.children)
                is Inline.Strike -> inlines(inline.children)
                is Inline.Highlight -> inlines(inline.children)
                is Inline.Link -> inlines(inline.children)
                else -> Unit
            }
        }
    }

    fun block(b: Block) {
        when (b) {
            is Block.Heading -> inlines(b.inlines)
            is Block.Paragraph -> inlines(b.inlines)
            is Block.ListItem -> inlines(b.inlines)
            is Block.Quote -> b.blocks.forEach(::block)
            is Block.Table -> (listOf(b.header) + b.rows).forEach { row -> row.forEach(::inlines) }
            else -> Unit
        }
    }
    blocks.forEach(::block)
    return refs
}

/** [this] with the checkbox at [box] flipped: one character, so every source range stays valid. */
fun Doc.withToggled(box: Int): Doc {
    fun block(b: Block): Block =
        when (b) {
            is Block.ListItem -> b.task?.takeIf { it.box == box }?.let { b.copy(task = it.copy(checked = !it.checked)) } ?: b
            is Block.Quote -> b.copy(blocks = b.blocks.map(::block))
            else -> b
        }
    return copy(blocks = blocks.map(::block))
}

/** Index of the block that holds source [offset], or the last one that starts before it. */
fun Doc.blockIndexAt(offset: Int): Int {
    if (blocks.isEmpty()) return 0
    var lo = 0
    var hi = blocks.lastIndex
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (blocks[mid].start <= offset) lo = mid else hi = mid - 1
    }
    return lo
}

/**
 * Where the caret goes when [oldText] is replaced by [newText] (a sync brought a new server version): the same line
 * number, clamped, and the same column when that line is long enough.
 */
fun caretAfterReload(
    oldText: String,
    oldCaret: Int,
    newText: String,
): Int {
    val caret = oldCaret.coerceIn(0, oldText.length)
    val line = oldText.substring(0, caret).count { it == '\n' }
    val column = caret - (oldText.lastIndexOf('\n', caret - 1).takeIf { caret > 0 } ?: -1) - 1
    var start = 0
    var current = 0
    while (current < line) {
        val next = newText.indexOf('\n', start)
        if (next < 0) break
        start = next + 1
        current++
    }
    val end = newText.indexOf('\n', start).let { if (it < 0) newText.length else it }
    return minOf(start + column, end)
}

/** The Task of the first checkbox at [box] in [this] text: `[ ]`, `[x]` or `[X]` as written. */
fun Task.expected(text: String): String? = if (box >= 0 && box + 3 <= text.length) text.substring(box, box + 3) else null
