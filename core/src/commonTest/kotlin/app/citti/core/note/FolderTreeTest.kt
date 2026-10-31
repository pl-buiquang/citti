package app.citti.core.note

import app.citti.core.settings.TreeSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class FolderTreeTest {
    private val byName = Comparator<String> { a, b -> a.compareTo(b) }

    /** Case-insensitive, like a PRIMARY-strength collator for these names. */
    private val ignoreCase = Comparator<String> { a, b -> a.lowercase().compareTo(b.lowercase()) }

    private fun FolderNode.paths(): List<String> = children.flatMap { listOf(it.path) + it.paths() }

    @Test
    fun intermediateFoldersAreCreated() {
        val root = buildFolderTree(listOf("a/b/c" to 2L), byName)
        assertEquals(listOf("a", "a/b", "a/b/c"), root.paths())
        val a = root.children.single()
        assertEquals(FolderNode("a", "a", 0, 2, 0, a.children), a)
        assertEquals("b", a.children.single().name)
        assertEquals(FolderNode("a/b/c", "c", 2, 2, 0, emptyList()), root.find("a/b/c"))
    }

    @Test
    fun countsAreRecursive() {
        val root = buildFolderTree(listOf("a" to 1L, "a/b" to 2L, "a/b/c" to 3L, "a/d" to 4L, "e" to 5L), byName)
        assertEquals(15, root.totalNotes)
        assertEquals(10, root.find("a")!!.totalNotes)
        assertEquals(1, root.find("a")!!.directNotes)
        assertEquals(5, root.find("a/b")!!.totalNotes)
        assertEquals(4, root.find("a/d")!!.totalNotes)
    }

    @Test
    fun rootHoldsTheRootNotes() {
        val root = buildFolderTree(listOf("" to 3L, "a" to 1L), byName)
        assertEquals("", root.path)
        assertEquals("", root.name)
        assertEquals(3, root.directNotes)
        assertEquals(4, root.totalNotes)

        val empty = buildFolderTree(emptyList(), byName)
        assertEquals(FolderNode("", "", 0, 0, 0, emptyList()), empty)
    }

    @Test
    fun childrenFollowTheCollator() {
        val counts = listOf("b" to 1L, "C" to 1L, "a" to 1L, "a/Z" to 1L, "a/y" to 1L)
        assertEquals(listOf("C", "a", "a/Z", "a/y", "b"), buildFolderTree(counts, byName).paths())
        assertEquals(listOf("a", "a/y", "a/Z", "b", "C"), buildFolderTree(counts, ignoreCase).paths())
        // Equal for the collator: the path breaks the tie.
        assertEquals(listOf("A", "a"), buildFolderTree(listOf("a" to 1L, "A" to 1L), ignoreCase).paths())
    }

    @Test
    fun find() {
        val root = buildFolderTree(listOf("a/b" to 1L, "ab" to 1L, "x" to 1L), byName)
        assertSame(root, root.find(""))
        assertEquals("a/b", root.find("a/b")?.path)
        assertEquals("ab", root.find("ab")?.path)
        assertEquals("a", root.find("a")?.path)
        assertNull(root.find("a/c"))
        assertNull(root.find("missing"))
        assertNull(root.find("a/b/c"))
    }

    @Test
    fun modifiedSortUsesTheNewestNoteRecursivelyAndFallsBackToTheName() {
        val counts = listOf("a" to 1L, "b/c" to 1L, "d" to 1L, "e" to 1L)
        val modified = mapOf("a" to 10L, "b/c" to 30L, "d" to 20L, "e" to 20L)
        val root = buildFolderTree(counts, byName, modified, TreeSort.MODIFIED)
        // b holds no Note itself, but its child is the newest.
        assertEquals(listOf("b", "b/c", "d", "e", "a"), root.paths())
        assertEquals(30L, root.find("b")?.modified)
        assertEquals(30L, root.modified)
        assertEquals(listOf("a", "b", "d", "e"), buildFolderTree(counts, byName, modified).children.map { it.path })
    }
}
