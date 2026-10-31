package app.citti.ui.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavigatorTest {
    @Test
    fun pushAndPop() {
        val nav = Navigator(listOf(Route.List()))
        nav.push(Route.Search("sync"))
        nav.push(Route.Note(7))
        assertEquals(Route.Note(7), nav.top)
        assertEquals(3, nav.stack.value.size)

        assertTrue(nav.pop())
        assertEquals(Route.Search("sync"), nav.top)
        assertTrue(nav.pop())
        assertEquals(listOf<Route>(Route.List()), nav.stack.value)
    }

    @Test
    fun popAtTheRootReturnsFalse() {
        val nav = Navigator(listOf(Route.Tree()))
        assertFalse(nav.pop())
        assertEquals(listOf<Route>(Route.Tree()), nav.stack.value)
    }

    @Test
    fun replaceTop() {
        val nav = Navigator(listOf(Route.List(), Route.Search("s")))
        nav.replaceTop(Route.Search("sy"))
        assertEquals(listOf(Route.List(), Route.Search("sy")), nav.stack.value)

        val root = Navigator(listOf(Route.List()))
        root.replaceTop(Route.Tree())
        assertEquals(listOf<Route>(Route.Tree()), root.stack.value)
    }

    @Test
    fun reset() {
        val nav = Navigator(listOf(Route.List(), Route.Note(1), Route.Settings))
        nav.reset(listOf(Route.Tree(""), Route.Tree("a"), Route.Tree("a/b")))
        assertEquals(Route.Tree("a/b"), nav.top)
        assertEquals(3, nav.stack.value.size)
        assertFailsWith<IllegalArgumentException> { nav.reset(emptyList()) }
    }

    @Test
    fun popToGoesBackToTheEntryWhenItIsOnTheStack() {
        val nav = Navigator(listOf(Route.Tree(""), Route.Tree("a"), Route.Tree("a/b"), Route.Note(3)))
        assertTrue(nav.popTo(Route.Tree("a")))
        assertEquals(listOf(Route.Tree(""), Route.Tree("a")), nav.stack.value)

        assertFalse(nav.popTo(Route.Tree("x")))
        assertEquals(listOf(Route.Tree(""), Route.Tree("a")), nav.stack.value)
    }

    @Test
    fun encodeDecodeRoundTripsAMixedStack() {
        val stack =
            listOf(
                Route.List(),
                Route.List(favoritesOnly = true),
                Route.Tree("projects/é t"),
                Route.Search("\"quoted\" text"),
                Route.Note(42),
                Route.Settings,
            )
        val encoded = Navigator(stack).encode()
        assertEquals(stack, Navigator.decode(encoded))
    }

    @Test
    fun decodeOfGarbageReturnsNull() {
        assertNull(Navigator.decode(""))
        assertNull(Navigator.decode("not json"))
        assertNull(Navigator.decode("[]"))
        assertNull(Navigator.decode("""[{"type":"app.citti.ui.nav.Route.Gone"}]"""))
        assertNull(Navigator.decode("""{"a":1}"""))
    }
}
