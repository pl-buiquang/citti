package app.citti.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.state.PreferencesGlanceStateDefinition
import app.citti.AndroidSyncLog
import app.citti.core.api.ApiError
import app.citti.core.sync.SyncOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Should the widgets redraw after this sync? Only when it changed something (or the credentials stopped working, so
 * they show "Sign in"): a no-change sync (one 304), a sync that was already running and a missing stack redraw nothing.
 */
fun shouldRefreshWidgets(outcome: SyncOutcome?): Boolean =
    when (outcome) {
        is SyncOutcome.Synced -> outcome.pushed + outcome.pulled + outcome.conflicts + outcome.dropped.size + outcome.deleteUndone > 0
        is SyncOutcome.Failed -> outcome.error is ApiError.Auth
        SyncOutcome.NothingChanged, SyncOutcome.AlreadyRunning, null -> false
    }

/**
 * Redraws the home-screen widgets. Every call runs on [scope] and swallows its failures, so a widget can never
 * block or fail a sync or an edit. With no widget placed the Glance calls find nothing to update.
 *
 * [todayNote] is the local id of today's Daily note (M6's local lookup), or null; it lets [updateNote] redraw the
 * Daily note widgets when the note that changed is today's.
 */
class WidgetRefresher(
    context: Context,
    private val scope: CoroutineScope,
    private val todayNote: suspend () -> Long?,
) {
    private val context = context.applicationContext

    /** All three widgets: sign-in, sign-out, favorites, titles, folders and Hidden folders changed. */
    fun updateAll() =
        launch("all") {
            FavoritesWidget().updateAll(context)
            NoteWidget().updateAll(context)
            DailyNoteWidget().updateAll(context)
        }

    private var appearanceJob: Job? = null

    /** The Appearance changed: one redraw of all widgets per [APPEARANCE_DEBOUNCE_MS], however many edits came in. */
    fun appearanceChanged() {
        appearanceJob?.cancel()
        appearanceJob =
            scope.launch {
                delay(APPEARANCE_DEBOUNCE_MS)
                updateAll()
            }
    }

    /** The Note widgets bound to [localId], and the Daily note widgets when [localId] is today's Daily note. */
    fun updateNote(localId: Long) =
        launch("note") {
            val manager = GlanceAppWidgetManager(context)
            val widget = NoteWidget()
            manager.getGlanceIds(NoteWidget::class.java).forEach { id ->
                val bound = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)[NOTE_WIDGET_LOCAL_ID]
                if (bound == localId) widget.update(context, id)
            }
            if (todayNote() == localId) DailyNoteWidget().updateAll(context)
        }

    /** The Daily note widgets and the Favorites widgets (the Today row's date). */
    fun updateDaily() =
        launch("daily") {
            DailyNoteWidget().updateAll(context)
            FavoritesWidget().updateAll(context)
        }

    private fun launch(
        kind: String,
        block: suspend () -> Unit,
    ) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never the title, the Folder or the text of a Note: the kind and the exception class only.
                AndroidSyncLog.warn("widget update failed kind=$kind error=${e::class.simpleName}")
            }
        }
    }
}

/** A burst of edits in the theme editor redraws the widgets once. */
internal const val APPEARANCE_DEBOUNCE_MS = 500L
