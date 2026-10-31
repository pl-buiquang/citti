package app.citti

import app.citti.ui.nav.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/**
 * The controllers of the back stack entries that have their own (Settings now; Search and Note later), keyed by
 * stack index and route. Each one gets its own scope from [newScope], cancelled when its entry leaves the stack
 * ([retain]) or on [clear]. List and Tree share the session's BrowseController, which is not kept here.
 */
class RouteHost(
    private val newScope: () -> CoroutineScope,
) {
    private class Entry(
        val route: Route,
        val scope: CoroutineScope,
        val controller: Any,
    )

    private val entries = mutableMapOf<Int, Entry>()

    /** The controller of the entry at [index], built by [create] in a fresh scope on first use. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> controller(
        index: Int,
        route: Route,
        create: (CoroutineScope) -> T,
    ): T {
        entries[index]?.takeIf { it.route == route }?.let { return it.controller as T }
        entries.remove(index)?.scope?.cancel()
        val scope = newScope()
        return create(scope).also { entries[index] = Entry(route, scope, it) }
    }

    /** Drops the entries that are no longer on [stack] at the same index. */
    fun retain(stack: List<Route>) {
        val gone = entries.filter { (index, entry) -> stack.getOrNull(index) != entry.route }.keys
        for (index in gone) entries.remove(index)?.scope?.cancel()
    }

    /** Sign out or sign in again: every entry goes. */
    fun clear() {
        entries.values.forEach { it.scope.cancel() }
        entries.clear()
    }

    /** For tests. */
    internal val size: Int get() = entries.size
}
