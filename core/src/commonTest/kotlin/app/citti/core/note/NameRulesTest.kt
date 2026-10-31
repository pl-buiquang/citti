package app.citti.core.note

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class NameRulesTest {
    private fun folderError(
        path: String,
        unsynced: List<String> = emptyList(),
    ): NameError {
        val failure = NameRules.normaliseFolder(path, unsynced).exceptionOrNull()
        return assertIs<InvalidNameException>(failure).error
    }

    @Test
    fun acceptsOrdinaryTitles() {
        listOf("Note", "2026-10-31", "a.b", "Été 2026 🌞", "x (2)", "note.").forEach { assertNull(NameRules.validateTitle(it), it) }
    }

    @Test
    fun refusesBlankSlashesUnsafeCharactersAndALeadingDot() {
        assertEquals(NameError.Blank, NameRules.validateTitle(""))
        assertEquals(NameError.Blank, NameRules.validateTitle("  \t"))
        assertEquals(NameError.Slash, NameRules.validateTitle("a/b"))
        assertEquals(NameError.Slash, NameRules.validateTitle("a\\b"))
        ":*?\"<>|".forEach { assertEquals(NameError.UnsafeCharacter, NameRules.validateTitle("a${it}b"), "$it") }
        assertEquals(NameError.UnsafeCharacter, NameRules.validateTitle("a\u0007b"))
        assertEquals(NameError.UnsafeCharacter, NameRules.validateTitle("a\nb"))
        assertEquals(NameError.LeadingDot, NameRules.validateTitle(".hidden"))
    }

    @Test
    fun normalisesFolderPaths() {
        assertEquals("a/b", NameRules.normaliseFolder("/a//b/", emptyList()).getOrThrow())
        assertEquals("a/b c", NameRules.normaliseFolder("  a / b c ", emptyList()).getOrThrow())
        assertEquals("", NameRules.normaliseFolder("", emptyList()).getOrThrow())
        assertEquals("", NameRules.normaliseFolder(" / ", emptyList()).getOrThrow())
    }

    @Test
    fun refusesDotSegmentsAndUnsafeCharactersInFolders() {
        assertEquals(NameError.DotSegment, folderError("a/../b"))
        assertEquals(NameError.DotSegment, folderError("./a"))
        assertEquals(NameError.DotSegment, folderError(".."))
        assertEquals(NameError.UnsafeCharacter, folderError("a/b:c"))
        assertEquals(NameError.Slash, folderError("a\\b"))
    }

    @Test
    fun refusesAFolderUnderAnUnsyncedPrefix() {
        val unsynced = listOf("archive")
        assertEquals(NameError.Unsynced, folderError("archive", unsynced))
        assertEquals(NameError.Unsynced, folderError("/archive/2025/", unsynced))
        // Same letters, another Folder.
        assertEquals("archived", NameRules.normaliseFolder("archived", unsynced).getOrThrow())
        // The Vault root is never under a prefix.
        assertEquals("", NameRules.normaliseFolder("", unsynced).getOrThrow())
    }
}
