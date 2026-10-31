package app.citti.core.note

private const val MAX_LINES = 20
private const val MAX_CHARS = 200

private val HEADING = Regex("""^ {0,3}#{1,6}(?:[ \t]+(.*?))?(?:[ \t]+#+)?[ \t]*$""")
private val LEADING_MARKERS =
    listOf(
        Regex("""^(?:>[ \t]?)+"""),
        Regex("""^(?:[-*+]|\d{1,9}[.)])[ \t]+(?:\[[ xX]\][ \t]*)?"""),
    )
private val EMBED = Regex("""!\[\[[^\]]*\]\]|!\[[^\]]*\]\([^)]*\)""")
private val WIKILINK_ALIAS = Regex("""\[\[[^\]|]*\|([^\]]*)\]\]""")
private val WIKILINK = Regex("""\[\[([^\]]*)\]\]""")
private val LINK = Regex("""\[([^\]]*)\]\([^)]*\)""")
private val EMPHASIS = Regex("""\*\*|__|~~|==|`""")
private val SPACES = Regex("""\s+""")

/**
 * The Excerpt of a Note: its first meaningful line, cleaned of markdown, at most 200 chars.
 *
 * Skips the frontmatter, blank lines, one-line HTML comments and a first heading that repeats [title]
 * (ignoring case). Returns null when [content] is null (not downloaded yet), and "" when no line
 * qualifies, so that [ExcerptBackfill] does not try the Note again.
 */
fun excerptOf(
    content: String?,
    title: String,
): String? {
    if (content == null) return null
    val lines = content.removePrefix("\uFEFF").lines()
    var i = skipFiller(lines, frontmatterEnd(lines))
    if (i < lines.size) {
        val heading = HEADING.matchEntire(lines[i])
        if (heading != null && heading.groupValues[1].trim().equals(title.trim(), ignoreCase = true)) i++
    }
    repeat(MAX_LINES) {
        i = skipFiller(lines, i)
        if (i >= lines.size) return ""
        val cleaned = clean(lines[i++])
        if (cleaned.isNotEmpty()) return truncate(cleaned)
    }
    return ""
}

/** Index of the first line after the frontmatter, or 0 when the text has none (or it is never closed). */
private fun frontmatterEnd(lines: List<String>): Int {
    if (lines.firstOrNull() != "---") return 0
    for (j in 1 until lines.size) if (lines[j] == "---" || lines[j] == "...") return j + 1
    return 0
}

private fun skipFiller(
    lines: List<String>,
    from: Int,
): Int {
    var i = from
    while (i < lines.size && isFiller(lines[i].trim())) i++
    return i
}

private fun isFiller(line: String) = line.isEmpty() || (line.startsWith("<!--") && line.endsWith("-->"))

private fun clean(line: String): String {
    var s = HEADING.matchEntire(line)?.let { it.groupValues[1] } ?: line.trim()
    for (marker in LEADING_MARKERS) s = marker.replace(s.trimStart(), "")
    s = EMBED.replace(s, "")
    s = WIKILINK_ALIAS.replace(s) { it.groupValues[1] }
    s = WIKILINK.replace(s) { it.groupValues[1].substringAfterLast('/') }
    s = LINK.replace(s) { it.groupValues[1] }
    s = EMPHASIS.replace(s, "")
    return SPACES.replace(s, " ").trim()
}

private fun truncate(s: String): String {
    if (s.length <= MAX_CHARS) return s
    val end = if (s[MAX_CHARS - 1].isHighSurrogate()) MAX_CHARS - 1 else MAX_CHARS
    return s.substring(0, end)
}
