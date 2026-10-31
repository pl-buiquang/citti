package app.citti

import app.citti.ui.nav.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RouteHostTest {
    private val scopes = mutableListOf<CoroutineScope>()
    private val host = RouteHost { CoroutineScope(Job()).also { scopes += it } }

    @Test
    fun sameEntryKeepsItsController() {
        val a = host.controller(1, Route.Settings) { Any() }
        assertSame(a, host.controller(1, Route.Settings) { Any() })
        assertEquals(1, scopes.size)
    }

    @Test
    fun entryLeavingTheStackIsCancelled() {
        host.controller(1, Route.Settings) { Any() }
        host.retain(listOf(Route.List(), Route.Settings))
        assertTrue(scopes.single().isActive)
        host.retain(listOf(Route.List()))
        assertFalse(scopes.single().isActive)
        assertEquals(0, host.size)
    }

    @Test
    fun anotherRouteAtTheSameIndexGetsANewController() {
        val a = host.controller(1, Route.Search("a")) { Any() }
        val b = host.controller(1, Route.Settings) { Any() }
        assertTrue(a !== b)
        assertFalse(scopes[0].isActive)
        assertTrue(scopes[1].isActive)
    }

    @Test
    fun searchKeepsItsControllerWhileTheQueryChanges() {
        val a = host.controller(1, Route.Search("a")) { Any() }
        host.retain(listOf(Route.List(), Route.Search("ab")))
        assertSame(a, host.controller(1, Route.Search("ab")) { Any() })
        assertTrue(scopes.single().isActive)
    }

    @Test
    fun clearCancelsEverything() {
        host.controller(1, Route.Settings) { Any() }
        host.controller(2, Route.Note(4)) { Any() }
        host.clear()
        assertTrue(scopes.none { it.isActive })
        assertEquals(0, host.size)
    }
}
