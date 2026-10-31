package app.citti.ui.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The hand-rolled back stack (no navigation library). The stack is never empty: [pop] refuses to
 * remove the root, so the caller lets the Activity finish instead. [encode] and [decode] are the
 * `rememberSaveable` format.
 */
class Navigator(
    initial: kotlin.collections.List<Route>,
) {
    init {
        require(initial.isNotEmpty()) { "The back stack needs a root route" }
    }

    private val entries = MutableStateFlow(initial)

    val stack: StateFlow<kotlin.collections.List<Route>> = entries.asStateFlow()

    val top: Route get() = entries.value.last()

    fun push(route: Route) {
        entries.update { it + route }
    }

    /** Removes the top entry. False, and nothing changes, when only the root is left. */
    fun pop(): Boolean {
        var popped = false
        entries.update { stack ->
            popped = stack.size > 1
            if (popped) stack.dropLast(1) else stack
        }
        return popped
    }

    /**
     * Pops back to the topmost entry equal to [route] (the Breadcrumb). False, and nothing changes,
     * when [route] is not on the stack.
     */
    fun popTo(route: Route): Boolean {
        var found = false
        entries.update { stack ->
            val index = stack.lastIndexOf(route)
            found = index >= 0
            if (found) stack.take(index + 1) else stack
        }
        return found
    }

    fun replaceTop(route: Route) {
        entries.update { it.dropLast(1) + route }
    }

    /** Drawer shortcuts, the ModeSwitch and (M7) widget deep links. */
    fun reset(routes: kotlin.collections.List<Route>) {
        require(routes.isNotEmpty()) { "The back stack needs a root route" }
        entries.value = routes
    }

    fun encode(): String = json.encodeToString(serializer, entries.value)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(Route.serializer())

        /** Null when [s] is not a saved stack (garbage, an empty list, or a route that no longer exists). */
        fun decode(s: String): kotlin.collections.List<Route>? =
            runCatching { json.decodeFromString(serializer, s) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
    }
}
