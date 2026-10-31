package app.citti.ui.browse

import app.citti.core.settings.MainMode
import app.citti.ui.nav.Navigator
import app.citti.ui.nav.Route
import app.citti.ui.nav.entryId

/** The root route of [this] Mode: where the app opens (`ui.main-mode`) and what the ModeSwitch goes back to. */
fun MainMode.rootRoute(): Route =
    when (this) {
        MainMode.LIST -> Route.List()
        MainMode.TREE -> Route.Tree()
        MainMode.CALENDAR -> Route.Calendar()
    }

/**
 * The ModeSwitch: swaps the main view to [mode] in place (the back stack becomes its root alone) and remembers it,
 * unless the stack already is that root alone (a Calendar on another month stays on its month).
 */
fun BrowseController.switchMode(
    navigator: Navigator,
    mode: MainMode,
) {
    val root = mode.rootRoute()
    val stack = navigator.stack.value
    if (stack.size == 1 && stack.single().entryId == root.entryId) return
    navigator.reset(listOf(root))
    setMode(mode)
}
