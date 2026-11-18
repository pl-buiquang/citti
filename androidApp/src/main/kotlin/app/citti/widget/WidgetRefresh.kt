package app.citti.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.glance.state.PreferencesGlanceStateDefinition

/**
 * Bumped by [refresh]. While a Glance session is alive, `update()` only reloads the widget's Glance state and
 * recomposes: a state loaded before `provideContent` would be drawn again unchanged. [rememberWidgetState] reloads
 * whenever this key changes.
 */
internal val WIDGET_REFRESH = longPreferencesKey("refresh")

/** The refresh key as stored now; read it before the first load and pass it to [rememberWidgetState]. */
internal suspend fun refreshKey(
    context: Context,
    id: GlanceId,
): Long? = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)[WIDGET_REFRESH]

/** Redraws the widget [id] with fresh data; [edit] also changes its Glance state (the Note picker's binding). */
internal suspend fun GlanceAppWidget.refresh(
    context: Context,
    id: GlanceId,
    edit: (MutablePreferences) -> Unit = {},
) {
    updateAppWidgetState(context, id) {
        edit(it)
        it[WIDGET_REFRESH] = (it[WIDGET_REFRESH] ?: 0L) + 1
    }
    update(context, id)
}

/** [refresh] for every placed widget of this class. */
internal suspend fun GlanceAppWidget.refreshAll(context: Context) {
    GlanceAppWidgetManager(context).getGlanceIds(this::class.java).forEach { refresh(context, it) }
}

/**
 * [initial] (loaded at [loadedAt], the refresh key read before it), then [load] again at each [refresh]. [load] gets
 * the current Glance state.
 */
@Composable
internal fun <T> rememberWidgetState(
    initial: T,
    loadedAt: Long?,
    load: suspend (Preferences) -> T,
): T {
    var state by remember { mutableStateOf(initial) }
    val prefs = currentState<Preferences>()
    val key = prefs[WIDGET_REFRESH]
    LaunchedEffect(key) { if (key != loadedAt) state = load(prefs) }
    return state
}
