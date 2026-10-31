package app.citti.widget

/** One favorite as the Favorites widget draws it. */
data class FavoriteRow(
    val localId: Long,
    val title: String,
    val category: String,
)

/** The columns the Note and Daily note widgets draw. [content] is null until the text is downloaded. */
data class NoteWidgetRow(
    val title: String,
    val category: String,
    val content: String?,
    val viewLine: Int?,
)

/** What the Daily note widget shows for today. */
sealed interface DailyWidgetState {
    /** The Journal folder is an Unsynced folder (M6 guard). */
    data object Guard : DailyWidgetState

    /** No Daily note for that date yet. */
    data object Missing : DailyWidgetState

    data class Found(
        val localId: Long,
        val row: NoteWidgetRow,
    ) : DailyWidgetState
}
