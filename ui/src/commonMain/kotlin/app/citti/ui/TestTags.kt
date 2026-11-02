package app.citti.ui

import app.citti.core.daily.PeriodKind
import app.citti.core.markdown.ToolbarAction
import app.citti.core.settings.ExclusionKind
import app.citti.ui.settings.SettingsSection

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
    const val TREE_SORT = "browse.treeSort"
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

    fun toolbar(action: ToolbarAction): String = "toolbar.${action.name.lowercase()}"

    fun settingsToolbarRow(action: ToolbarAction): String = "settings.toolbar.${action.name.lowercase()}"

    const val IMAGE_SHEET = "image.sheet"
    const val IMAGE_CAMERA = "image.camera"
    const val IMAGE_GALLERY = "image.gallery"
    const val UPLOAD_CHIP = "upload.chip"

    /**
     * The Calendar: its scrolling body (pulled down to refresh), the month pager (swiped by the Robots), the Today
     * chip (Index's Today button reads "Today" too) and the Daily note rows under the grid. Day cells are found by
     * their content description.
     */
    const val CALENDAR_BODY = "calendar.body"
    const val CALENDAR_GRID = "calendar.grid"
    const val CALENDAR_TODAY = "calendar.today"
    const val CALENDAR_MONTH_NOTE = "calendar.month"
    const val CALENDAR_YEAR_NOTE = "calendar.year"
    const val CALENDAR_QUARTER_NOTE = "calendar.quarter"

    /** The week number cell of the grid row that starts on [firstDay] (an ISO date). */
    fun calendarWeek(firstDay: String) = "calendar.week.$firstDay"

    const val CALENDAR_ENTRIES = "calendar.entries"

    /** The bar at the bottom of the Note screen: the previous and next Daily note, else the previous and next Note of the Folder. */
    const val NEIGHBOR_PREVIOUS = "note.neighborPrevious"
    const val NEIGHBOR_NEXT = "note.neighborNext"

    const val SETTINGS_HOME = "settings.home"

    /** The scrolling body of a Settings section page, and the version line under the menu. */
    const val SETTINGS_SECTION = "settings.section"
    const val SETTINGS_VERSION = "settings.version"
    const val APPEARANCE_EDIT_CUSTOM = "appearance.edit-custom"
    const val THEME_EDITOR = "appearance.editor"
    const val THEME_PREVIEW = "appearance.preview"
    const val THEME_IMPORT = "appearance.import"
    const val THEME_EXPORT = "appearance.export"
    const val THEME_CONFIRM = "appearance.confirm"
    const val THEME_COLOR_SHEET = "appearance.color-sheet"
    const val THEME_FONT_SHEET = "appearance.font-sheet"
    const val THEME_HEX = "appearance.hex"
    const val THEME_CONTRAST = "appearance.contrast"
    const val THEME_SHEET_DONE = "appearance.sheet-done"

    fun themeFace(face: app.citti.core.appearance.FontFace) = "appearance.face.${face.id}"

    const val THEME_IMPORT_ERROR = "appearance.import-error"
    const val THEME_DENSITY = "appearance.density"
    const val THEME_CORNERS = "appearance.corners"
    const val THEME_CAPITALS = "appearance.capitals"

    fun themeColor(token: app.citti.core.appearance.ColorToken) = "appearance.color.${token.key}"

    fun themeFont(role: app.citti.core.appearance.FontRole) = "appearance.${role.key}"

    /** The root of the app: its `stateDescription` is `<family>:<light|dark>:<accent>` (E2E tests cannot read colors). */
    const val THEME_STATE = "theme.state"

    fun appearanceTheme(theme: app.citti.core.appearance.ThemeChoice) = "appearance.theme.${theme.name.lowercase()}"

    fun appearanceMode(mode: app.citti.core.appearance.ModeChoice) = "appearance.mode.${mode.name.lowercase()}"

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
    const val SETTINGS_SHOW_WEEKS = "settings.daily.weeks"
    const val SETTINGS_SHOW_QUARTER = "settings.daily.quarter"

    /** The title format field and the Template row of the Week, Month, Quarter or Year note. */
    fun settingsPeriodFormat(kind: PeriodKind): String = "settings.daily.${kind.name.lowercase()}.format"

    fun settingsPeriodTemplate(kind: PeriodKind): String = "settings.daily.${kind.name.lowercase()}.template"

    const val SETTINGS_IMPORT_REPORT = "settings.daily.import.report"

    /** A Date locale choice; null is Automatic. */
    fun settingsDateLocale(code: String?): String = "settings.daily.locale.${code ?: "auto"}"

    /** A Week starts on choice ("MONDAY"…); null is Automatic. */
    fun settingsWeekStart(day: String?): String = "settings.daily.week.${day?.lowercase() ?: "auto"}"

    fun settingsAdd(kind: ExclusionKind): String = "settings.${kind.name.lowercase()}.add"

    fun settingsSection(section: SettingsSection): String = "settings.menu.${section.name.lowercase()}"

    fun settingsRemove(
        kind: ExclusionKind,
        path: String,
    ): String = "settings.${kind.name.lowercase()}.remove.$path"
}
