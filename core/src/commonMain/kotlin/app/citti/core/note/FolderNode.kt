package app.citti.core.note

import app.citti.core.settings.TreeSort

/**
 * A Folder of Tree mode. [path] is the full category ("" for the Vault root) and [name] its last segment.
 * [directNotes] counts the visible Notes in this Folder itself, [totalNotes] those in it and below it. [modified] is
 * the newest `modified` of the visible Notes in it and below it (0 when there is none).
 */
data class FolderNode(
    val path: String,
    val name: String,
    val directNotes: Int,
    val totalNotes: Int,
    val modified: Long,
    val children: List<FolderNode>,
)

/** A `countVisibleByFolder` row: the visible [notes] directly in [category], the newest one modified at [newest]. */
data class FolderCount(
    val category: String,
    val notes: Long,
    val newest: Long,
)

/**
 * Builds the tree from `countVisibleByFolder` rows (category, visible Notes). "" is the Vault root. Intermediate
 * Folders are created from the paths (`a/b/c` implies `a` and `a/b`) even when they hold no Note directly.
 * [modified] maps a Folder to the newest `modified` of the Notes directly in it. Children are sorted per [sort]: by
 * name with [collator], or by [FolderNode.modified] newest first, then by name; always then by path so that the order
 * is stable.
 *
 * Hidden and DELETED Notes are not counted by the query, so a Folder holding only those does not appear.
 */
fun buildFolderTree(
    counts: List<Pair<String, Long>>,
    collator: Comparator<String>,
    modified: Map<String, Long> = emptyMap(),
    sort: TreeSort = TreeSort.ALPHABETICAL,
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
    val byName = compareBy<FolderNode, String>(collator) { it.name }
    val order =
        when (sort) {
            TreeSort.ALPHABETICAL -> byName.thenBy { it.path }
            TreeSort.MODIFIED -> compareByDescending<FolderNode> { it.modified }.then(byName).thenBy { it.path }
        }

    fun node(path: String): FolderNode {
        val children = childrenOf[path].orEmpty().map(::node).sortedWith(order)
        val own = direct.getValue(path)
        val newest = maxOf(modified[path] ?: 0L, children.maxOfOrNull { it.modified } ?: 0L)
        return FolderNode(path, path.substringAfterLast('/'), own, own + children.sumOf { it.totalNotes }, newest, children)
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
