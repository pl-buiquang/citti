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

    /**
     * The Calendar mode at [month] ("2026-10"; null is the current month). A month change replaces the route
     * (`replaceTop`), so the month lasts for the entry and survives process death.
     */
    @Serializable
    data class Calendar(
        val month: String? = null,
    ) : Route

    /** Search; [query] follows what is typed (`replaceTop`), so it survives process death. */
    @Serializable
    data class Search(
        val query: String = "",
    ) : Route

    /**
     * A Note. [preview] is the Edit/Preview toggle of this entry (`replaceTop`, so it survives process death without
     * becoming a back stack entry); [searchQuery] is the Search query that opened it (used by in-note search).
     * [focus] opens it in Edit mode with the editor focused and the keyboard up (a New note); the controller clears
     * it once applied (`replaceTop`), so it is not applied again after rotation or process death. [notice] is a banner
     * shown on the Note until dismissed (a new Daily note whose Template was not found); dismissing clears it.
     */
    @Serializable
    data class Note(
        val localId: Long,
        val preview: Boolean = false,
        val searchQuery: String? = null,
        val focus: Boolean = false,
        val notice: NoteNotice? = null,
        /** Opens scrolled so this source line (0-based) is at the top, caret at its start, keyboard closed (a widget tap). */
        val startLine: Int? = null,
    ) : Route

    /** The full-screen Image viewer: [link] is the Image link as written in the Note [noteLocalId]. */
    @Serializable
    data class ImageViewer(
        val noteLocalId: Long,
        val link: String,
    ) : Route

    @Serializable
    data object Settings : Route
}

/**
 * What identifies a back stack entry for its controller and saved UI state: the route itself, except that a
 * Search entry stays the same entry while its query changes, a Calendar entry while its month changes, and a Note
 * entry while its mode or prefill changes.
 */
val Route.entryId: Route
    get() =
        when (this) {
            is Route.Search -> Route.Search()
            is Route.Calendar -> Route.Calendar()
            is Route.Note -> Route.Note(localId)
            else -> this
        }

/** A banner a Note route carries until it is dismissed. */
@Serializable
sealed interface NoteNotice {
    /** The new Daily note was created empty: its Template [path] was found nowhere. */
    @Serializable
    data class TemplateMissing(
        val path: String,
    ) : NoteNotice
}
