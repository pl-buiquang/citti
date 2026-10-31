package app.citti.ui.nav

import kotlinx.serialization.Serializable

/** A screen of the signed-in app. Serializable so the back stack survives process death. */
@Serializable
sealed interface Route {
    /** List mode; [favoritesOnly] is the drawer's Favorites entry. */
    @Serializable
    data class List(
        val favoritesOnly: Boolean = false,
    ) : Route

    /** Tree mode at [folder] ("" is the Vault root). */
    @Serializable
    data class Tree(
        val folder: String = "",
    ) : Route

    @Serializable
    data class Search(
        val query: String = "",
    ) : Route

    @Serializable
    data class Note(
        val localId: Long,
    ) : Route

    @Serializable
    data object Settings : Route
}
