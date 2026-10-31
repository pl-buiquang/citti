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

/** The Mode the main view opens in: List mode, Tree mode or the Calendar. Stored by [name]. */
enum class MainMode { LIST, TREE, CALENDAR }

/** Reads and writes the UI settings in the `setting` table. An unknown stored value falls back to [MainMode.LIST]. */
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

    private fun parseMode(raw: String?): MainMode = MainMode.entries.firstOrNull { it.name == raw } ?: MainMode.LIST
}
