package app.citti.ui.components

import app.citti.core.note.find
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FolderPickerTest {
    private val byName = Comparator<String> { a, b -> a.compareTo(b) }

    @Test
    fun hiddenFoldersAreListedAndUnsyncedOnesAreNot() {
        val folders =
            pickerFolders(
                counts = listOf("" to 1L, "archive" to 2L, "work/a" to 3L, "private/x" to 4L),
                hidden = listOf("archive"),
                unsynced = listOf("private"),
                collator = byName,
            )
        assertEquals(listOf("archive", "work"), folders.tree.children.map { it.path })
        assertEquals(3, folders.tree.find("work")?.totalNotes)
        assertNull(folders.tree.find("private"))
        assertEquals(listOf("archive"), folders.hidden)
    }

    @Test
    fun backGoesUpOneLevelAndClosesAtTheRoot() {
        val state = FolderPickerState("a/b")
        assertEquals("a", state.back()?.path)
        assertEquals("", state.back()?.back()?.path)
        assertNull(FolderPickerState("").back())
        assertEquals(FolderPickerState("a/b"), state.startNewFolder().back())
    }

    @Test
    fun aNewFolderIsRelativeToTheShownFolder() {
        assertEquals("x", FolderPickerState("").onNewFolder(" x ").typedPath())
        assertEquals("a/x/y", FolderPickerState("a").onNewFolder("x/y").typedPath())
    }
}
