package app.citti.core.settings

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.citti.core.db.CittiDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** `setting` key of the main view's Mode. */
const val KEY_UI_MAIN_MODE = "ui.main-mode"

/** `setting` key of the Tree mode's sort order. */
const val KEY_UI_TREE_SORT = "ui.tree-sort"

/** How Tree mode orders Folders and Notes: by name, or newest first (a Folder by its newest Note, recursively). */
enum class TreeSort { ALPHABETICAL, MODIFIED }

/** The Mode the main view opens in: List mode, Tree mode or the Calendar. Stored by [name]. */
enum class MainMode { LIST, TREE, CALENDAR }

/** Reads and writes the UI settings in the `setting` table. An unknown stored value falls back to [MainMode.LIST] and [TreeSort.ALPHABETICAL]. */
class UiSettings(
    db: CittiDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val queries = db.settingQueries

    fun mainMode(): MainMode = parseMode(queries.selectSetting(KEY_UI_MAIN_MODE).executeAsOneOrNull())

    fun setMainMode(mode: MainMode) = queries.upsertSetting(KEY_UI_MAIN_MODE, mode.name)

    fun mainModeFlow(): Flow<MainMode> =
        queries
            .selectSetting(KEY_UI_MAIN_MODE)
            .asFlow()
            .mapToOneOrNull(dispatcher)
            .map(::parseMode)

    fun treeSort(): TreeSort = parseSort(queries.selectSetting(KEY_UI_TREE_SORT).executeAsOneOrNull())

    fun setTreeSort(sort: TreeSort) = queries.upsertSetting(KEY_UI_TREE_SORT, sort.name)

    fun treeSortFlow(): Flow<TreeSort> =
        queries
            .selectSetting(KEY_UI_TREE_SORT)
            .asFlow()
            .mapToOneOrNull(dispatcher)
            .map(::parseSort)

    private fun parseSort(raw: String?): TreeSort = TreeSort.entries.firstOrNull { it.name == raw } ?: TreeSort.ALPHABETICAL

    private fun parseMode(raw: String?): MainMode = MainMode.entries.firstOrNull { it.name == raw } ?: MainMode.LIST
}
