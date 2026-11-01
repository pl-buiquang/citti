package app.citti.core.note

import app.citti.core.settings.TreeSort

/** A Note of the Folder of an open Note, as `selectSiblings` returns it. */
data class Sibling(
    val localId: Long,
    val title: String,
    val modified: Long,
)

/** The Notes right before and after one in its Folder, in the order Tree mode lists them. */
data class SiblingNeighbors(
    val previous: Sibling?,
    val next: Sibling?,
)

/**
 * The Notes of [rows] (one Folder) before and after [localId], ordered as Tree mode does for [sort]: by title with
 * [collator], or newest first, then by title; always then by local id so that the order is stable. A [localId] that is
 * not in [rows] has no neighbors.
 */
fun siblingsAround(
    rows: List<Sibling>,
    localId: Long,
    sort: TreeSort,
    collator: Comparator<String>,
): SiblingNeighbors {
    val byTitle = compareBy<Sibling, String>(collator) { it.title }
    val order =
        when (sort) {
            TreeSort.ALPHABETICAL -> byTitle.thenBy { it.localId }
            TreeSort.MODIFIED -> compareByDescending<Sibling> { it.modified }.then(byTitle).thenBy { it.localId }
        }
    val sorted = rows.sortedWith(order)
    val at = sorted.indexOfFirst { it.localId == localId }
    if (at < 0) return SiblingNeighbors(null, null)
    return SiblingNeighbors(sorted.getOrNull(at - 1), sorted.getOrNull(at + 1))
}
