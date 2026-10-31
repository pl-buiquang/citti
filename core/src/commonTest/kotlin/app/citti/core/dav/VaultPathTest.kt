package app.citti.core.dav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VaultPathTest {
    private fun resolve(
        folder: String,
        link: String,
    ) = VaultPath.resolve(folder, link)?.value

    @Test
    fun resolvesRelativeLinks() {
        assertEquals("media/x.png", resolve("a/b", "../../media/x.png"))
        assertEquals("a/b/x.png", resolve("a/b", "./x.png"))
        assertEquals("a/b/sub/x.png", resolve("a/b", "sub/x.png"))
        assertEquals("x.png", resolve("", "x.png"))
        assertEquals("media/x.png", resolve("a/b", "/media/x.png"))
    }

    @Test
    fun decodesEscapesFromTheLink() {
        assertEquals("Screenshot from 2025.png", resolve("", "Screenshot%20from%202025.png"))
        assertEquals("été/a.png", resolve("", "%C3%A9t%C3%A9/a.png"))
        assertEquals("100%.png", resolve("", "100%.png"))
    }

    @Test
    fun neverLeavesTheVault() {
        assertNull(resolve("a", "../../x.png"))
        assertNull(resolve("", "../x.png"))
        assertNull(resolve("a", ".."))
        assertNull(resolve("", ""))
    }

    @Test
    fun parseKeepsTheTextAsIs() {
        assertEquals("a%20b/c.png", VaultPath.parse("a%20b/c.png")?.value)
        assertNull(VaultPath.parse("../x"))
    }
}
