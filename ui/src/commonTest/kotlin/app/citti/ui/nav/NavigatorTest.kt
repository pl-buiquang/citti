package app.citti.ui.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
                Route.ImageViewer(42, "supernote/a b.png|900"),
                Route.Settings,
            )
        val encoded = Navigator(stack).encode()
        assertEquals(stack, Navigator.decode(encoded))
    }

    @Test
    fun aStackSavedBeforeThePreviewToggleDecodesWithDefaults() {
        val saved = """[{"type":"app.citti.ui.nav.Route.Note","localId":7}]"""
        assertEquals(listOf<Route>(Route.Note(7)), Navigator.decode(saved))
        val toggled = Route.Note(7, preview = true, searchQuery = "q")
        assertEquals(listOf<Route>(toggled), Navigator.decode(Navigator(listOf(toggled)).encode()))
    }

    @Test
    fun aNoteKeepsItsEntryWhenTheModeOrPrefillChanges() {
        assertEquals(Route.Note(7).entryId, Route.Note(7, preview = true, searchQuery = "q").entryId)
        assertNotEquals(Route.Note(7).entryId, Route.Note(8).entryId)
    }

    @Test
    fun decodeOfGarbageReturnsNull() {
        assertNull(Navigator.decode(""))
        assertNull(Navigator.decode("not json"))
        assertNull(Navigator.decode("[]"))
        assertNull(Navigator.decode("""[{"type":"app.citti.ui.nav.Route.Gone"}]"""))
        assertNull(Navigator.decode("""{"a":1}"""))
    }

    @Test
    fun anM4StackWithoutFocusDecodes() {
        val m4 =
            "[{\"type\":\"app.citti.ui.nav.Route.List\"}," +
                "{\"type\":\"app.citti.ui.nav.Route.Note\",\"localId\":3,\"preview\":true,\"searchQuery\":\"q\"}]"
        assertEquals(listOf(Route.List(), Route.Note(3, preview = true, searchQuery = "q")), Navigator.decode(m4))
    }

    @Test
    fun focusRoundTripsAndIsPartOfTheSameEntry() {
        val nav = Navigator(listOf(Route.List(), Route.Note(3, focus = true)))
        assertEquals(nav.stack.value, Navigator.decode(nav.encode()))
        assertEquals(Route.Note(3).entryId, Route.Note(3, focus = true).entryId)
    }

    @Test
    fun anM5StackWithoutNoticeDecodes() {
        val m5 =
            "[{\"type\":\"app.citti.ui.nav.Route.Tree\",\"folder\":\"work\"}," +
                "{\"type\":\"app.citti.ui.nav.Route.Note\",\"localId\":3,\"preview\":true,\"focus\":true}]"
        assertEquals(listOf(Route.Tree("work"), Route.Note(3, preview = true, focus = true)), Navigator.decode(m5))
    }

    @Test
    fun aNoteNoticeRoundTripsAndIsPartOfTheSameEntry() {
        val note = Route.Note(3, focus = true, notice = NoteNotice.TemplateMissing("templates/daily.md"))
        val nav = Navigator(listOf(Route.List(), note))
        assertEquals(nav.stack.value, Navigator.decode(nav.encode()))
        assertEquals(Route.Note(3).entryId, note.entryId)
    }

    @Test
    fun aCalendarRoundTripsAndKeepsItsEntryWhenTheMonthChanges() {
        val stack = listOf(Route.Calendar(), Route.Calendar("2026-09"), Route.Note(3, notice = NoteNotice.TemplateMissing("t.md")))
        assertEquals(stack, Navigator.decode(Navigator(stack).encode()))
        assertEquals(Route.Calendar(), Route.Calendar("2026-09").entryId)
        assertEquals(Route.Calendar().entryId, Route.Calendar("2026-10").entryId)
    }

    @Test
    fun anM5StackWithoutCalendarDecodes() {
        val m5 =
            "[{\"type\":\"app.citti.ui.nav.Route.List\"}," +
                "{\"type\":\"app.citti.ui.nav.Route.Search\",\"query\":\"q\"}," +
                "{\"type\":\"app.citti.ui.nav.Route.Note\",\"localId\":3,\"focus\":true}]"
        assertEquals(listOf(Route.List(), Route.Search("q"), Route.Note(3, focus = true)), Navigator.decode(m5))
        val calendar = """[{"type":"app.citti.ui.nav.Route.Calendar"}]"""
        assertEquals(listOf<Route>(Route.Calendar()), Navigator.decode(calendar))
    }
}
