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

    /** The Note ⋮ menu: the button, the menu, the dialogs' fields and confirm buttons, the Note info sheet. */
    const val NOTE_MENU_BUTTON = "note.menuButton"
    const val NOTE_MENU = "note.menu"
    const val RENAME_FIELD = "rename.field"
    const val RENAME_CONFIRM = "rename.confirm"
    const val DELETE_CONFIRM = "delete.confirm"
    const val NOTE_INFO = "note.info"

    /** Insert image: the toolbar button, the source sheet and its rows, the upload chip. */
    const val TOOLBAR_IMAGE = "toolbar.image"
    const val IMAGE_SHEET = "image.sheet"
    const val IMAGE_CAMERA = "image.camera"
    const val IMAGE_GALLERY = "image.gallery"
    const val UPLOAD_CHIP = "upload.chip"

    /**
     * The Calendar: the month pager (swiped by the Robots), the Today chip (Index's Today button reads "Today" too)
     * and the Daily note rows under the grid. Day cells are found by their content description.
     */
    const val CALENDAR_GRID = "calendar.grid"
    const val CALENDAR_TODAY = "calendar.today"
    const val CALENDAR_ENTRIES = "calendar.entries"

    const val SETTINGS_HOME = "settings.home"
    const val SETTINGS_PICKER = "settings.picker"
    const val SETTINGS_FOLDER_PATH = "settings.folder.path"
    const val SETTINGS_CONFIRM = "settings.confirm"

    /** Settings › Daily notes: its rows, the Template picker, the Import report dialog and the guard banner. */
    const val SETTINGS_DAILY_GUARD = "settings.daily.guard"
    const val SETTINGS_JOURNAL_FOLDER = "settings.daily.folder"
    const val SETTINGS_TEMPLATE = "settings.daily.template"
    const val SETTINGS_TEMPLATE_SEARCH = "settings.daily.template.search"
    const val SETTINGS_TEMPLATE_NONE = "settings.daily.template.none"
    const val SETTINGS_DATE_FORMAT = "settings.daily.format"
    const val SETTINGS_IMPORT = "settings.daily.import"
    const val SETTINGS_IMPORT_REPORT = "settings.daily.import.report"

    /** A Date locale choice; null is Automatic. */
    fun settingsDateLocale(code: String?): String = "settings.daily.locale.${code ?: "auto"}"

    /** A Week starts on choice ("MONDAY"…); null is Automatic. */
    fun settingsWeekStart(day: String?): String = "settings.daily.week.${day?.lowercase() ?: "auto"}"

    fun settingsAdd(kind: ExclusionKind): String = "settings.${kind.name.lowercase()}.add"

    fun settingsRemove(
        kind: ExclusionKind,
        path: String,
    ): String = "settings.${kind.name.lowercase()}.remove.$path"
}
