package app.citti.core.search

/**
 * A MATCH string for `note_fts`. [phrases] is the number of phrases in it, which is the phrase count of
 * `matchinfo(note_fts, 'x')`. [titleOnly] is true for a one-character query, which searches titles only.
 */
data class FtsQuery(
    val match: String,
    val phrases: Int,
    val titleOnly: Boolean,
)

/**
 * Turns what the user typed into an FTS4 MATCH string, or null when nothing searchable is left.
 *
 * Words are ANDed and each one is a prefix (`sync et` → `"sync*" "et*"`). A `"quoted text"` span is a phrase;
 * an unbalanced quote runs to the end. Every term is quoted, so FTS operators the user types (`OR`, `NOT`,
 * `NEAR`, `-`, `:`) are plain text. A term with no letter or digit is dropped, as unicode61 would drop it.
 * A query of a single letter or digit becomes `title:a*`: FTS4 applies a column filter to unquoted terms only,
 * and one letter or digit cannot be an operator.
 */
fun toFtsMatch(raw: String): FtsQuery? {
    val terms = tokenize(raw).filter { term -> term.text.any { it.isLetterOrDigit() } }
    if (terms.isEmpty()) return null
    val single = terms.singleOrNull()
    if (single != null && !single.phrase && single.text.length == 1 && single.text[0].isLetterOrDigit()) {
        return FtsQuery(match = "title:${single.text}*", phrases = 1, titleOnly = true)
    }
    val match = terms.joinToString(" ") { if (it.phrase) "\"${it.text}\"" else "\"${it.text}*\"" }
    return FtsQuery(match = match, phrases = terms.size, titleOnly = false)
}

private class Term(
    val text: String,
    val phrase: Boolean,
)

/** Splits on whitespace and keeps `"…"` spans together. A quote also ends the word before it. */
private fun tokenize(raw: String): List<Term> {
    val terms = mutableListOf<Term>()
    val current = StringBuilder()
    var inPhrase = false

    fun flush(phrase: Boolean) {
        // Collapse the whitespace of a phrase; quotes are already split out.
        val text =
            current
                .toString()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .joinToString(" ")
        if (text.isNotEmpty()) terms += Term(text, phrase)
        current.clear()
    }
    for (c in raw) {
        when {
            c == '"' -> {
                flush(inPhrase)
                inPhrase = !inPhrase
            }

            c.isWhitespace() && !inPhrase -> {
                flush(phrase = false)
            }

            else -> {
                current.append(c)
            }
        }
    }
    flush(inPhrase)
    return terms
}
