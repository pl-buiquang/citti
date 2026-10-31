package app.citti.ui

import app.citti.core.settings.ExclusionKind

/**
 * `testTag`s shared by the screens and the E2E Robots, named `<screen>.<element>`. Only nodes with no unique
 * text or content description get one; the Robots find the others by their text.
 */
object TestTags {
    const val LOGIN_SERVER = "login.server"
    const val LOGIN_USER = "login.user"
    const val LOGIN_APP_PASSWORD = "login.appPassword"

    /** The rows of List and Tree mode. */
    const val BROWSE_ROWS = "browse.rows"

    const val SEARCH_RESULTS = "search.results"

    /** The wrapper of the Edit mode `EditText` (ADR 0009); the Robot reaches the view itself through Espresso. */
    const val NOTE_EDITOR = "note.editor"

    /** The blocks of Preview mode. */
    const val NOTE_PREVIEW = "note.preview"

    /** The New note button; Index's label and the dialog title both read "New note". */
    const val NEW_NOTE_BUTTON = "browse.newNote"
    const val NEW_NOTE_TITLE = "newNote.title"
    const val NEW_NOTE_CHANGE_FOLDER = "newNote.changeFolder"
    const val NEW_NOTE_CREATE = "newNote.create"
    const val TITLE_CLASH_OPEN = "titleClash.open"
    const val TITLE_CLASH_CREATE = "titleClash.create"

    /** The Folder rows of the Folder picker. */
    const val FOLDER_PICKER = "folderPicker.rows"
    const val FOLDER_PICKER_NEW_FOLDER = "folderPicker.newFolder"
    const val FOLDER_PICKER_CONFIRM = "folderPicker.confirm"

    const val SETTINGS_HOME = "settings.home"
    const val SETTINGS_PICKER = "settings.picker"
    const val SETTINGS_FOLDER_PATH = "settings.folder.path"
    const val SETTINGS_CONFIRM = "settings.confirm"

    fun settingsAdd(kind: ExclusionKind): String = "settings.${kind.name.lowercase()}.add"

    fun settingsRemove(
        kind: ExclusionKind,
        path: String,
    ): String = "settings.${kind.name.lowercase()}.remove.$path"
}
