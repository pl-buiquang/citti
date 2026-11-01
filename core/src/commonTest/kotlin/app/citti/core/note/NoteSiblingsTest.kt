package app.citti.core.note

import app.citti.core.settings.TreeSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NoteSiblingsTest {
    private val rows =
        listOf(
            Sibling(1, "banana", 10),
            Sibling(2, "Apple", 30),
            Sibling(3, "cherry", 20),
        )
    private val collator = Comparator<String> { a, b -> a.lowercase().compareTo(b.lowercase()) }

    @Test
    fun alphabeticalFollowsTheCollator() {
        val around = siblingsAround(rows, 1, TreeSort.ALPHABETICAL, collator)
        assertEquals(2L, around.previous?.localId)
        assertEquals(3L, around.next?.localId)
    }

    @Test
    fun modifiedIsNewestFirst() {
        val around = siblingsAround(rows, 3, TreeSort.MODIFIED, collator)
        assertEquals(2L, around.previous?.localId)
        assertEquals(1L, around.next?.localId)
    }

    @Test
    fun theEndsHaveOneSideOnly() {
        assertNull(siblingsAround(rows, 2, TreeSort.ALPHABETICAL, collator).previous)
        assertNull(siblingsAround(rows, 3, TreeSort.ALPHABETICAL, collator).next)
    }

    @Test
    fun aNoteOutsideTheRowsHasNoNeighbors() {
        assertEquals(SiblingNeighbors(null, null), siblingsAround(rows, 99, TreeSort.ALPHABETICAL, collator))
    }

    @Test
    fun equalTitlesFallBackToTheLocalId() {
        val same = listOf(Sibling(5, "x", 1), Sibling(4, "x", 1))
        assertEquals(4L, siblingsAround(same, 5, TreeSort.ALPHABETICAL, collator).previous?.localId)
    }
}
