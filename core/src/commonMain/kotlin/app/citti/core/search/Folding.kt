package app.citti.core.search

/** [text] folded for matching, and for each folded character the offset of the source character it came from. */
class Folded(
    val text: String,
    /** `offsets[i]` is the source index of folded char `i`; the last entry is the source length. */
    val offsets: IntArray,
) {
    /** The source range covered by the folded range `[start, end)`. */
    fun sourceRange(
        start: Int,
        end: Int,
    ): IntRange {
        val from = offsets[start]
        var to = offsets[end - 1] + 1
        // A folded `oe` from `œ` ends in the middle of one source character: it is still one character.
        if (to > offsets.last()) to = offsets.last()
        return from until to
    }
}

private const val FROM = "àáâãäåāăąçćĉċčďđèéêëēĕėęěĝğġģĥħìíîïĩīĭįıĵķĺļľŀłñńņňŉòóôõöøōŏőŕŗřśŝşšţťŧùúûüũūŭůűųŵýÿŷźżž"
private const val TO = "aaaaaaaaacccccddeeeeeeeeegggghhiiiiiiiiijklllllnnnnnooooooooorrrssssttt" + "uuuuuuuuuuwyyyzzz"

private val SINGLE: Map<Char, Char> = FROM.zip(TO).toMap()

/**
 * Lowercases and strips the common Latin accents, like `unicode61 remove_diacritics=1` in the search index, so
 * `ete` finds `Été`. `œ`, `æ` and `ß` become two letters. Only for matching; the text itself is never changed.
 */
fun fold(text: String): Folded {
    val sb = StringBuilder(text.length)
    val offsets = IntArray(text.length * 2 + 1)
    var n = 0
    for ((i, ch) in text.withIndex()) {
        val c = ch.lowercaseChar()
        val expansion =
            when (c) {
                'œ' -> "oe"
                'æ' -> "ae"
                'ß' -> "ss"
                else -> null
            }
        if (expansion != null) {
            for (e in expansion) {
                sb.append(e)
                offsets[n++] = i
            }
        } else {
            sb.append(SINGLE[c] ?: c)
            offsets[n++] = i
        }
    }
    offsets[n] = text.length
    return Folded(sb.toString(), offsets.copyOf(n + 1))
}

fun foldedText(text: String): String = fold(text).text
