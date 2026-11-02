package app.citti.core.settings

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.citti.core.db.CittiDatabase
import app.citti.core.markdown.ToolbarAction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** `setting` key of the format toolbar's [ToolbarLayout]. */
const val KEY_EDITOR_TOOLBAR = "editor.toolbar"

/**
 * Which buttons the format toolbar shows and in what order: [order] holds every [ToolbarAction] once, [hidden] the
 * ones left out. Stored as the names in order, a hidden one prefixed with `-` (`Bold,Italic,-Strike,Heading`).
 */
data class ToolbarLayout(
    val order: List<ToolbarAction>,
    val hidden: Set<ToolbarAction> = emptySet(),
) {
    /** The buttons the toolbar shows, in order. */
    val visible: List<ToolbarAction> get() = order.filter { it !in hidden }

    fun toggle(action: ToolbarAction): ToolbarLayout = copy(hidden = if (action in hidden) hidden - action else hidden + action)

    /** Swaps [action] with its neighbour in [order] (hidden ones included, so the page rows move one step). */
    fun move(
        action: ToolbarAction,
        up: Boolean,
    ): ToolbarLayout {
        val from = order.indexOf(action)
        val to = if (up) from - 1 else from + 1
        if (from < 0 || to !in order.indices) return this
        val next = order.toMutableList()
        next[from] = order[to]
        next[to] = action
        return copy(order = next)
    }

    fun encode(): String = order.joinToString(",") { if (it in hidden) "-${it.name}" else it.name }

    companion object {
        val DEFAULT = ToolbarLayout(ToolbarAction.entries.toList())

        /**
         * The layout stored as [raw]; absent is [DEFAULT]. Unknown and repeated names are dropped, and a button the
         * stored value does not mention (added by a later release) comes last, hidden.
         */
        fun parse(raw: String?): ToolbarLayout {
            if (raw == null) return DEFAULT
            val order = mutableListOf<ToolbarAction>()
            val hidden = mutableSetOf<ToolbarAction>()
            for (token in raw.split(',')) {
                val off = token.startsWith('-')
                val action = ToolbarAction.entries.firstOrNull { it.name == token.removePrefix("-") } ?: continue
                if (action in order) continue
                order += action
                if (off) hidden += action
            }
            for (action in ToolbarAction.entries) {
                if (action !in order) {
                    order += action
                    hidden += action
                }
            }
            return ToolbarLayout(order, hidden)
        }
    }
}

/** Reads and writes the editor settings in the `setting` table. */
class EditorSettings(
    db: CittiDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val queries = db.settingQueries

    fun toolbar(): ToolbarLayout = ToolbarLayout.parse(queries.selectSetting(KEY_EDITOR_TOOLBAR).executeAsOneOrNull())

    fun setToolbar(layout: ToolbarLayout) {
        queries.upsertSetting(KEY_EDITOR_TOOLBAR, layout.encode())
    }

    fun toolbarFlow(): Flow<ToolbarLayout> =
        queries
            .selectSetting(KEY_EDITOR_TOOLBAR)
            .asFlow()
            .mapToOneOrNull(dispatcher)
            .map(ToolbarLayout::parse)
}
