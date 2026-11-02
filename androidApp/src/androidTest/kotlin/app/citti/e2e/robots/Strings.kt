package app.citti.e2e.robots

/** The English UI copy the Robots look for (`ui/src/commonMain/composeResources/values/strings.xml`). */
object Strings {
    const val SIGN_IN_BROWSER = "Sign in with browser"
    const val USE_APP_PASSWORD = "Enter app password manually"
    const val USE_BROWSER = "Sign in with browser instead"
    const val SIGN_IN = "Sign in"
    const val CANCEL = "Cancel"
    const val WAITING_BROWSER = "Approve the sign-in in your browser, then come back to citti."
    const val ERROR_INVALID_URL = "This is not a usable server address."
    const val ERROR_NOTES_MISSING = "The Notes app is not installed or enabled on this server."
    const val ERROR_WRONG_CREDENTIALS = "The server refused these credentials."
    const val ERROR_OFFLINE = "Cannot reach the server. Check your connection."

    const val SEARCH_HINT = "Search notes"
    const val OPEN_MENU = "Open menu"
    const val SYNC_NOW = "Sync now"
    const val MODE_LIST = "List"
    const val MODE_TREE = "Tree"
    const val TREE_ROOT = "Notes"
    const val FAVORITES = "Favorites"
    const val SETTINGS = "Settings"
    const val SETTINGS_VERSION = "citti "
    const val FIRST_SYNC_EMPTY = "Your notes are on their way"
    const val BANNER_AUTH_FAILED = "The server refused the saved sign-in. Your Notes stay on this device."
    const val BANNER_SIGN_IN_AGAIN = "Sign in again"

    const val BACK = "Back"
    const val NOTE_EDIT = "Edit"
    const val NOTE_PREVIEW = "Preview"
    const val NOTE_UNDO = "Undo"
    const val TOOLBAR_BOLD = "Bold"
    const val TOOLBAR_STRIKE = "Strikethrough"
    const val TOOLBAR_CHECKBOX = "Checkbox"
    const val SAVE_OFFLINE = "Offline (will sync)"
    const val BADGE_PENDING = "Pending"
    const val BADGE_CONFLICT = "Conflict"
    const val IMAGE_MISSING = "Image not found"

    const val SETTINGS_ADD = "Add"
    const val SETTINGS_SIGN_OUT = "Sign out"

    const val NEW_NOTE = "New note"
    const val CREATE = "Create"
    const val NEW_FOLDER = "New folder…"

    /** The New note dialog's Folder line; the Vault root shows as [TREE_ROOT]. */
    fun newNoteFolder(folder: String) = "Folder: ${folder.ifEmpty { TREE_ROOT }}"

    fun titleClashCreate(title: String) = "Create “$title”"

    const val MENU_FAVORITE_ADD = "Add to favorites"
    const val MENU_FAVORITE_REMOVE = "Remove from favorites"
    const val MENU_MOVE = "Move…"
    const val MENU_RENAME = "Rename…"
    const val MENU_INSERT_IMAGE = "Insert image"
    const val MENU_NOTE_INFO = "Note info"
    const val MENU_DELETE = "Delete"
    const val TOAST_FAVORITE_ADDED = "Added to favorites"
    const val TOAST_FAVORITE_REMOVED = "Removed from favorites"
    const val TOAST_DELETED = "Deleted"

    /** Rename and Move refused: the target Folder has the title. */
    fun clashError(
        title: String,
        folder: String,
    ) = "A note named “$title” already exists in “${folder.ifEmpty { TREE_ROOT }}”"

    const val INFO_TITLE = "Title"
    const val INFO_FOLDER = "Folder"
    const val INFO_WORDS = "Words"
    const val INFO_LINES = "Lines"
    const val INFO_STATE = "Sync state"
    const val INFO_FAVORITE = "Favorite"
    const val INFO_READONLY = "Read-only"
    const val INFO_SYNCED = "Synced"
    const val INFO_PENDING = "Pending"
    const val YES = "Yes"
    const val NO = "No"

    const val TODAY_NOTE = "Today's note"
    const val MODE_CALENDAR = "Calendar"
    const val CALENDAR_PREVIOUS_MONTH = "Previous month"
    const val CALENDAR_NEXT_MONTH = "Next month"
    const val DROPPED_TODAY = "Today's note already existed on the server"

    /** A Calendar day cell, read by TalkBack: "30 October, daily note". */
    fun calendarDayNote(day: String) = "$day, daily note"

    fun calendarDayNone(day: String) = "$day, no note"

    /** The Calendar's past day dialog: "Create daily note for 28 Oct?". */
    fun createPastDay(day: String) = "Create daily note for $day?"

    fun templateMissing(path: String) = "Template “$path” not found"

    const val SETTINGS_IMPORT = "Import from Obsidian"

    /** Adding an Unsynced folder that covers the Journal folder. */
    fun exclusionJournalRefused(journal: String) = "The Journal folder “$journal” is inside it. Change the Journal folder first."

    /** A line of the Import from Obsidian report: a value refused, and why. */
    fun importRefused(
        value: String,
        reason: String,
    ) = "Not applied: $value ($reason)"

    fun journalUnsyncedRefused(path: String) = "“$path” is an Unsynced folder"

    const val IMPORT_OK = "OK"
}
