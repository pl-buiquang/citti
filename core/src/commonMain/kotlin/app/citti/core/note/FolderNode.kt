package app.citti.core.note

/**
 * A Folder of Tree mode. [path] is the full category ("" for the Vault root) and [name] its last segment.
 * [directNotes] counts the visible Notes in this Folder itself, [totalNotes] those in it and below it.
 */
data class FolderNode(
    val path: String,
    val name: String,
    val directNotes: Int,
    val totalNotes: Int,
    val children: List<FolderNode>,
)

/**
 * Builds the tree from `countVisibleByFolder` rows (category, visible Notes). "" is the Vault root. Intermediate
 * Folders are created from the paths (`a/b/c` implies `a` and `a/b`) even when they hold no Note directly.
 * Children are sorted by name with [collator], then by path so that the order is stable.
 *
 * Hidden and DELETED Notes are not counted by the query, so a Folder holding only those does not appear.
 */
fun buildFolderTree(
    counts: List<Pair<String, Long>>,
    collator: Comparator<String>,
): FolderNode {
    val direct = mutableMapOf("" to 0)
    for ((category, notes) in counts) {
        direct[category] = (direct[category] ?: 0) + notes.toInt()
        var parent = parentOf(category)
        while (parent !in direct) {
            direct[parent] = 0
            parent = parentOf(parent)
        }
    }
    val childrenOf = direct.keys.filter { it.isNotEmpty() }.groupBy(::parentOf)
    val order = compareBy<FolderNode, String>(collator) { it.name }.thenBy { it.path }

    fun node(path: String): FolderNode {
        val children = childrenOf[path].orEmpty().map(::node).sortedWith(order)
        val own = direct.getValue(path)
        return FolderNode(path, path.substringAfterLast('/'), own, own + children.sumOf { it.totalNotes }, children)
    }
    return node("")
}

/** The Folder at [path] in this tree, or null when it holds no visible Note. */
fun FolderNode.find(path: String): FolderNode? {
    if (path == this.path) return this
    if (this.path.isNotEmpty() && !path.startsWith("${this.path}/")) return null
    return children.firstNotNullOfOrNull { it.find(path) }
}

private fun parentOf(path: String): String = path.substringBeforeLast('/', missingDelimiterValue = "")
