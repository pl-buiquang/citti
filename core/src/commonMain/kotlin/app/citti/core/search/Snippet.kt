package app.citti.core.search

private const val HIT_START = '\u0002'
private const val LEAD = 24
private val SPACES = Regex("""\s+""")

/**
 * Makes a `snippet()` result fit the one-line Search row: newlines and runs of spaces become one space, and
 * when the first hit is further than [LEAD] chars in, the text before it is cut at a word so that the hit is
 * visible instead of being pushed off the ellipsized line.
 */
fun tidySnippet(raw: String): String {
    val text = SPACES.replace(raw, " ").trim()
    val hit = text.indexOf(HIT_START)
    if (hit <= LEAD) return text
    val cut = text.lastIndexOf(' ', hit - LEAD)
    return if (cut <= 0) text else "…" + text.substring(cut + 1)
}
