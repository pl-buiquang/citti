package app.citti.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class BreadcrumbTest {
    @Test
    fun pathsStartAtTheRoot() {
        assertEquals(listOf(""), breadcrumbPaths(""))
        assertEquals(listOf("", "a", "a/b", "a/b/c"), breadcrumbPaths("a/b/c"))
    }
}
