package app.citti.core.search

/** Columns of `note_fts`, in the order of its CREATE statement. */
private const val COLUMNS = 3
private const val TITLE = 0
private const val CONTENT = 1

/**
 * Hits of phrase [phrase] in column [column] for this row, from a `matchinfo(note_fts, 'x')` blob: 3 unsigned
 * 32-bit ints per (phrase, column), the first one being the hits in this row. The blob is in native byte order,
 * little-endian on every Android ABI and on the JVM test hosts.
 */
fun hitsInRow(
    info: ByteArray,
    phrase: Int,
    column: Int,
): Long {
    val offset = 4 * 3 * (phrase * COLUMNS + column)
    if (offset + 4 > info.size) return 0
    var value = 0L
    for (i in 3 downTo 0) value = (value shl 8) or (info[offset + i].toLong() and 0xFF)
    return value
}

/**
 * The Search tier of a row: 1 when every phrase is in the title, 2 when every phrase is in the title or the
 * content, 3 otherwise (at least one phrase is only in the Folder path).
 */
fun tierOf(
    info: ByteArray,
    phrases: Int,
): Int {
    val ps = 0 until phrases
    return when {
        ps.all { hitsInRow(info, it, TITLE) > 0 } -> 1
        ps.all { hitsInRow(info, it, TITLE) > 0 || hitsInRow(info, it, CONTENT) > 0 } -> 2
        else -> 3
    }
}
