package app.citti.core.fake

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixtureVaultTest {
    private val vault = FixtureVault.load()

    @Test
    fun indexListsExactlyTheFilesOfTheFolder() {
        val dir = File(FixtureVault::class.java.getResource("/fixtures/vault")!!.toURI())
        val onDisk =
            dir
                .walkTopDown()
                .filter { it.isFile && it.name != "fixture.json" }
                .map { it.relativeTo(dir).invariantSeparatorsPath }
                .toSortedSet()
        val indexed = (vault.notes.map { it.path } + vault.files.keys).map(FixtureVault::resourcePath).toSortedSet()
        assertEquals(onDisk, indexed)
        assertTrue(".obsidian/app.json" in vault.files)
    }

    @Test
    fun notesCarryTheirFolderTitleAndMetadata() {
        val alpha = vault.note("alpha")
        assertEquals("work/projects", alpha.category)
        assertTrue(alpha.content.startsWith("# Alpha"))
        assertEquals(
            listOf("Roadmap", "todo", "Welcome"),
            vault.notes
                .filter { it.favorite }
                .sortedByDescending { it.modified }
                .map { it.title },
        )
        assertEquals(1001L, vault.note("todo").id)
        assertEquals(
            3000,
            vault
                .note("long")
                .content
                .lines()
                .size - 1,
        )
        assertEquals(3, vault.files.keys.count { it.startsWith("attachments/") })
    }

    @Test
    fun seedKeepsFixedIdsAndServesTheOtherFilesOverDav() {
        val server = FakeNotesServer()
        val ids = server.seed(vault)
        assertEquals(vault.notes.size, server.notes.size)
        assertEquals(1001L, ids["todo.md"])
        assertEquals(1003L, ids["work/images.md"])
        assertEquals("work/projects", server.note(ids.getValue("work/projects/Duplicate.md"))!!.category)
        assertTrue(server.fileEtag("attachments/by-root.png") != null)
        assertTrue(server.fileEtag(".obsidian/app.json") != null)
        // A note added later never collides with a fixed id.
        assertTrue(server.addNote("New") !in ids.values)
    }
}
