package app.citti.core.note

import app.citti.core.dav.VaultPath
import app.citti.core.dav.percentDecode
import app.citti.core.db.CittiDatabase
import app.citti.core.search.foldedText
import app.citti.core.sync.categoryMatchesAny

sealed interface LinkTarget {
    data class Note(
        val localId: Long,
    ) : LinkTarget

    /** The target lies under an Unsynced folder: it exists on the server but not on this device. */
    data class Unsynced(
        val path: String,
    ) : LinkTarget

    data object Missing : LinkTarget
}

data class Suggestion(
    val localId: Long,
    val title: String,
    val folder: String,
)

/**
 * Finds the Note a wikilink or a markdown link points at, and suggests titles while a wikilink is typed. Hidden
 * folders resolve; DELETED Notes do not. Blocking: call it on `Dispatchers.IO`.
 */
class LinkResolver(
    db: CittiDatabase,
) {
    private val notes = db.noteQueries
    private val settings = db.settingQueries

    /** [target] is what is between `[[` and `]]` before any `|`; [currentFolder] is the folder of the Note holding it. */
    fun resolve(
        currentFolder: String,
        target: String,
    ): LinkTarget {
        val t = clean(target)
        if (t.isEmpty()) return LinkTarget.Missing
        if ('/' in t) {
            val folder = t.substringBeforeLast('/').trim('/')
            return byPath(folder, t.substringAfterLast('/'))
        }
        notes.selectByFolderAndTitle(currentFolder, t).executeAsOneOrNull()?.let { return LinkTarget.Note(it) }
        val all = notes.selectByTitle(t).executeAsList()
        // The unique match, or the most recently modified of the duplicates (the query is ordered).
        return all.firstOrNull()?.let { LinkTarget.Note(it.local_id) } ?: LinkTarget.Missing
    }

    /** A markdown link to a `.md` file: relative to [currentFolder] first, then as a path from the Vault root. */
    fun resolveMarkdownLink(
        currentFolder: String,
        url: String,
    ): LinkTarget {
        val path = url.substringBefore('#').substringBefore('?')
        var firstUnsynced: LinkTarget? = null
        for (folder in listOf(currentFolder, "")) {
            val p = VaultPath.resolve(folder, path) ?: continue
            val found = byPath(p.folder, p.name.removeSuffix(".md").removeSuffix(".MD"))
            if (found is LinkTarget.Note) return found
            if (found is LinkTarget.Unsynced && firstUnsynced == null) firstUnsynced = found
        }
        return firstUnsynced ?: LinkTarget.Missing
    }

    private fun byPath(
        folder: String,
        title: String,
    ): LinkTarget {
        notes.selectByFolderAndTitle(folder, title).executeAsOneOrNull()?.let { return LinkTarget.Note(it) }
        val unsynced = settings.selectExclusionsByKind("UNSYNCED").executeAsList()
        return if (folder.isNotEmpty() &&
            categoryMatchesAny(folder, unsynced)
        ) {
            LinkTarget.Unsynced("$folder/$title")
        } else {
            LinkTarget.Missing
        }
    }

    /** `#heading` and `^block` removed, a trailing `.md` removed, percent escapes decoded. */
    private fun clean(target: String): String =
        percentDecode(target.substringBefore('#').substringBefore('^'))
            .trim()
            .let { if (it.endsWith(".md", ignoreCase = true)) it.dropLast(3) else it }
            .trim()

    /**
     * The best 8 Notes for what was typed after `[[`: an exact title first, then titles that start with it, then the
     * rest, each by most recently modified. The empty prefix gives the 8 most recently modified Notes.
     */
    fun suggest(prefix: String): List<Suggestion> {
        val typed = prefix.substringAfterLast('/').trim()
        val words = typed.split { !it.isLetterOrDigit() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) {
            return notes.selectRecentForSuggest().executeAsList().map { Suggestion(it.local_id, it.title, it.category) }
        }
        // Unquoted terms take the column filter; an FTS operator word is left out (quoted terms ignore the filter).
        val safe = words.filter { it !in OPERATORS && !it.startsWith("NEAR") }.ifEmpty { words.take(1) }
        val match = safe.joinToString(" ") { if (it in OPERATORS || it.startsWith("NEAR")) "\"$it*\"" else "title:$it*" }
        val folded = foldedText(typed)
        val rows = notes.suggestTitles(match).executeAsList()
        return rows
            .sortedWith(compareBy<app.citti.core.db.SuggestTitles> { rank(foldedText(it.title), folded) }.thenByDescending { it.modified })
            .take(8)
            .map { Suggestion(it.local_id, it.title, it.category) }
    }

    private fun rank(
        title: String,
        typed: String,
    ) = when {
        title == typed -> 0
        title.startsWith(typed) -> 1
        else -> 2
    }

    private companion object {
        val OPERATORS = setOf("AND", "OR", "NOT")
    }
}

private fun String.split(isSeparator: (Char) -> Boolean): List<String> {
    val out = ArrayList<String>()
    val sb = StringBuilder()
    for (c in this) {
        if (isSeparator(c)) {
            out += sb.toString()
            sb.setLength(0)
        } else {
            sb.append(c)
        }
    }
    out += sb.toString()
    return out
}
