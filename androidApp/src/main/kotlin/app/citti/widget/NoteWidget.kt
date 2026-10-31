package app.citti.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.Text
import app.citti.R
import app.citti.core.note.NoteWindow
import app.citti.core.note.NoteWindows
import app.citti.ui.theme.NotesSpacing

/** What the Note widget draws. */
sealed interface NoteWidgetState {
    data object SignedOut : NoteWidgetState

    /** No Note picked yet, or it is gone: a tap opens the Note picker for this widget. */
    data class Unavailable(
        val appWidgetId: Int,
    ) : NoteWidgetState

    /** The Note's text has not been downloaded yet. */
    data class Downloading(
        val localId: Long,
        val title: String,
        val category: String,
    ) : NoteWidgetState

    /** [viewLine] is the Reading position, the line a tap opens (null: none yet). */
    data class Ready(
        val localId: Long,
        val title: String,
        val category: String,
        val viewLine: Int?,
        val window: NoteWindow,
    ) : NoteWidgetState
}

/** The Note a widget shows is its `localId` preference. */
val NOTE_WIDGET_LOCAL_ID = longPreferencesKey("localId")

class NoteWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val prefs = getAppWidgetState<Preferences>(context, id)
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val state = loadNoteState(context, prefs[NOTE_WIDGET_LOCAL_ID], appWidgetId)
        provideContent { NoteContent(state) }
    }
}

class NoteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NoteWidget()
}

/** Reads the local DB only; never the network. */
internal suspend fun loadNoteState(
    context: Context,
    localId: Long?,
    appWidgetId: Int,
): NoteWidgetState {
    if (!isSignedIn(context)) return NoteWidgetState.SignedOut
    if (localId == null) return NoteWidgetState.Unavailable(appWidgetId)
    val row = context.container().noteWidget(localId) ?: return NoteWidgetState.Unavailable(appWidgetId)
    val content = row.content ?: return NoteWidgetState.Downloading(localId, row.title, row.category)
    return NoteWidgetState.Ready(localId, row.title, row.category, row.viewLine, NoteWindows.slice(content, row.viewLine))
}

@Composable
fun NoteContent(state: NoteWidgetState) {
    val context = LocalContext.current
    Column(GlanceModifier.fillMaxSize().background(WidgetColors.bg).appWidgetBackground()) {
        when (state) {
            NoteWidgetState.SignedOut -> {
                CenteredState(context.getString(R.string.widget_signed_out), WidgetIntents.app(context))
            }

            is NoteWidgetState.Unavailable -> {
                CenteredState(context.getString(R.string.widget_note_gone), WidgetIntents.picker(context, state.appWidgetId))
            }

            is NoteWidgetState.Downloading -> {
                val open = WidgetIntents.note(context, state.localId)
                NoteHeader(state.title, state.category, open)
                Text(
                    context.getString(R.string.widget_note_downloading),
                    GlanceModifier.padding(horizontal = NotesSpacing.space4).clickable(actionStartActivity(open)),
                    style = WidgetText.state,
                )
            }

            is NoteWidgetState.Ready -> {
                val open = WidgetIntents.note(context, state.localId, state.viewLine)
                NoteHeader(state.title, state.category, open)
                NoteBody(state.window, open)
            }
        }
    }
}

@Composable
internal fun CenteredState(
    text: String,
    open: Intent,
) {
    Box(
        GlanceModifier.fillMaxSize().clickable(actionStartActivity(open)).padding(NotesSpacing.space4),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = WidgetText.state) }
}

@Composable
internal fun NoteHeader(
    title: String,
    category: String,
    open: Intent,
) {
    val context = LocalContext.current
    Column(
        GlanceModifier
            .fillMaxWidth()
            .clickable(actionStartActivity(open))
            .padding(horizontal = NotesSpacing.space4, vertical = NotesSpacing.space2),
    ) {
        Text(title, style = WidgetText.title, maxLines = 1)
        Text(category.ifEmpty { context.getString(R.string.widget_root_folder) }, style = WidgetText.folder, maxLines = 1)
    }
}

/** The read-only plain-text body of a Note, from the [window], shared by the Note and Daily note widgets. Every tap opens [open]. */
@Composable
internal fun NoteBody(
    window: NoteWindow,
    open: Intent,
) {
    val context = LocalContext.current
    val click = GlanceModifier.fillMaxWidth().clickable(actionStartActivity(open))
    LazyColumn(GlanceModifier.fillMaxSize()) {
        itemsIndexedLines(window, click)
        if (window.more) {
            item(itemId = MORE_ITEM_ID) {
                Text(
                    context.getString(R.string.widget_note_more),
                    click.padding(horizontal = NotesSpacing.space4, vertical = NotesSpacing.space2),
                    style = WidgetText.more,
                )
            }
        }
    }
}

private const val MORE_ITEM_ID = -3L

private fun androidx.glance.appwidget.lazy.LazyListScope.itemsIndexedLines(
    window: NoteWindow,
    click: GlanceModifier,
) {
    window.lines.forEachIndexed { index, line ->
        item(itemId = (window.firstLine + index).toLong()) {
            // A blank source line still takes a line of height.
            Text(line.ifEmpty { " " }, click.padding(horizontal = NotesSpacing.space4), style = WidgetText.body)
        }
    }
}
