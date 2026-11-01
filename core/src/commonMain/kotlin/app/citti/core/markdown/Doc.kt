package app.citti.core.markdown

/** An inline run of the Preview model. */
sealed interface Inline {
    data class Text(
        val text: String,
    ) : Inline

    data class Bold(
        val children: List<Inline>,
    ) : Inline

    data class Italic(
        val children: List<Inline>,
    ) : Inline

    data class Strike(
        val children: List<Inline>,
    ) : Inline

    data class Highlight(
        val children: List<Inline>,
    ) : Inline

    data class Code(
        val text: String,
    ) : Inline

    /** A link to a web address (or anything that is not a Note). */
    data class Link(
        val url: String,
        val children: List<Inline>,
    ) : Inline

    /** A markdown link to a `.md` file: [path] as written, `#heading` included. */
    data class NoteLink(
        val path: String,
        val children: List<Inline>,
    ) : Inline

    /** `[[target]]` or `[[target|alias]]`, [target] as written. */
    data class Wikilink(
        val target: String,
        val alias: String?,
    ) : Inline

    /** [src] as written (`![alt](src)` or `![[src]]` of an image file). [width] and [height] come from `|900` or `|900x300`. */
    data class Image(
        val src: String,
        val alt: String,
        val width: Int?,
        val height: Int?,
    ) : Inline

    /** `![[file]]` of something that is not an image. */
    data class Embed(
        val name: String,
    ) : Inline

    data object LineBreak : Inline
}

enum class Align { None, Left, Center, Right }

/** One frontmatter entry; a list value has several [values]. */
data class Property(
    val key: String,
    val values: List<String>,
)

/**
 * A block of the Preview model. [start] and [end] are offsets into the source text, in order and not overlapping,
 * so the blocks cover the text without reordering it. Lists are flattened into one [ListItem] per item and long
 * paragraphs and code into chunks, so a lazy list can show a 9k-item note one item at a time.
 */
sealed interface Block {
    val start: Int
    val end: Int

    /** The folded frontmatter. [entries] is null when it could not be read as `key: value` lines; show [raw]. */
    data class Properties(
        override val start: Int,
        override val end: Int,
        val entries: List<Property>?,
        val raw: String,
    ) : Block

    data class Heading(
        override val start: Int,
        override val end: Int,
        val level: Int,
        val inlines: List<Inline>,
    ) : Block

    data class Paragraph(
        override val start: Int,
        override val end: Int,
        val inlines: List<Inline>,
        val depth: Int = 0,
    ) : Block

    /** A paragraph that holds only images. */
    data class ImageBlock(
        override val start: Int,
        override val end: Int,
        val images: List<Inline.Image>,
        val depth: Int = 0,
    ) : Block

    data class ListItem(
        override val start: Int,
        override val end: Int,
        val depth: Int,
        val ordered: Boolean,
        /** The number of an ordered item. */
        val number: Int?,
        val task: Task?,
        val inlines: List<Inline>,
    ) : Block

    data class Quote(
        override val start: Int,
        override val end: Int,
        val blocks: List<Block>,
    ) : Block

    /** [continued] is true for a chunk after the first of a long block. */
    data class Code(
        override val start: Int,
        override val end: Int,
        val info: String?,
        val text: String,
        val depth: Int = 0,
        val continued: Boolean = false,
    ) : Block

    data class Table(
        override val start: Int,
        override val end: Int,
        val header: List<List<Inline>>,
        val rows: List<List<List<Inline>>>,
        val align: List<Align>,
    ) : Block

    data class Rule(
        override val start: Int,
        override val end: Int,
    ) : Block
}

/**
 * A task item's checkbox. [box] is the offset of its `[` in the source text; the three characters there are
 * `[ ]` or `[x]` (also `[X]`).
 */
data class Task(
    val checked: Boolean,
    val box: Int,
)

/**
 * [gaps] is parallel to [blocks] (or empty): the blank lines the source has before block *i*, beyond the one that
 * separates two paragraphs. Only set between top-level paragraphs, images and headings.
 */
data class Doc(
    val blocks: List<Block>,
    val gaps: List<Int> = emptyList(),
) {
    fun gapBefore(index: Int): Int = gaps.getOrElse(index) { 0 }
}

/** The text a reader sees, for in-note search. */
fun List<Inline>.plainText(): String =
    joinToString("") {
        when (it) {
            is Inline.Text -> it.text
            is Inline.Bold -> it.children.plainText()
            is Inline.Italic -> it.children.plainText()
            is Inline.Strike -> it.children.plainText()
            is Inline.Highlight -> it.children.plainText()
            is Inline.Code -> it.text
            is Inline.Link -> it.children.plainText()
            is Inline.NoteLink -> it.children.plainText()
            is Inline.Wikilink -> it.alias ?: it.target.substringAfterLast('/')
            is Inline.Image -> it.alt
            is Inline.Embed -> it.name
            Inline.LineBreak -> "\n"
        }
    }

fun Block.plainText(): String =
    when (this) {
        is Block.Properties -> entries?.joinToString("\n") { "${it.key}: ${it.values.joinToString(", ")}" } ?: raw
        is Block.Heading -> inlines.plainText()
        is Block.Paragraph -> inlines.plainText()
        is Block.ImageBlock -> images.joinToString("\n") { it.alt }
        is Block.ListItem -> inlines.plainText()
        is Block.Quote -> blocks.joinToString("\n") { it.plainText() }
        is Block.Code -> text
        is Block.Table -> (listOf(header) + rows).joinToString("\n") { row -> row.joinToString("\t") { it.plainText() } }
        is Block.Rule -> ""
    }
