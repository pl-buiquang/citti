package app.citti.core.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FolderPrefixTest {
    @Test
    fun matchesTheFolderItselfAndSubFolders() {
        assertTrue(categoryMatchesPrefix("a/b", "a/b"))
        assertTrue(categoryMatchesPrefix("a/b/c", "a/b"))
        assertTrue(categoryMatchesPrefix("a/b/c/d", "a"))
    }

    @Test
    fun doesNotMatchSiblingsSharingLetters() {
        assertFalse(categoryMatchesPrefix("a/bc", "a/b"))
        assertFalse(categoryMatchesPrefix("ab", "a"))
        assertFalse(categoryMatchesPrefix("a", "a/b"))
        assertFalse(categoryMatchesPrefix("", "a"))
    }

    @Test
    fun matchesAny() {
        assertTrue(categoryMatchesAny("x/y", listOf("a", "x")))
        assertFalse(categoryMatchesAny("x/y", emptyList()))
    }
}
