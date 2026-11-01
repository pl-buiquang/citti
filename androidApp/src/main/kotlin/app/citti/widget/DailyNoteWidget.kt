package app.citti.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import app.citti.R
import app.citti.core.note.NoteWindow
import app.citti.core.note.NoteWindows
import app.citti.ui.theme.NotesSpacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** What the Daily note widget draws; [date] is today's date in the phone locale. */
sealed interface DailyNoteState {
    data object SignedOut : DailyNoteState

    /** The Journal folder is an Unsynced folder (M6 guard). */
    data class Guard(
        val date: String,
    ) : DailyNoteState

    data class Missing(
        val date: String,
    ) : DailyNoteState

    data class Downloading(
        val date: String,
    ) : DailyNoteState

    data class Ready(
        val date: String,
        val window: NoteWindow,
    ) : DailyNoteState
}

class DailyNoteWidget : GlanceAppWidget() {
    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        DayScheduler.schedule(context)
        val state = loadDailyState(context)
        provideContent { WidgetThemeHost(context) { DailyNoteContent(state) } }
    }
}

class DailyNoteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DailyNoteWidget()

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                DayScheduler.cancelIfUnused(context)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Reads the local DB only; today is resolved here at draw time, and again by the app at the tap. */
internal suspend fun loadDailyState(context: Context): DailyNoteState {
    if (!isSignedIn(context)) return DailyNoteState.SignedOut
    val container = context.container()
    val date = dateLabel(container.todayDate())
    return when (val daily = container.dailyWidget(container.todayDate())) {
        DailyWidgetState.Guard -> {
            DailyNoteState.Guard(date)
        }

        DailyWidgetState.Missing -> {
            DailyNoteState.Missing(date)
        }

        is DailyWidgetState.Found -> {
            val content = daily.row.content ?: return DailyNoteState.Downloading(date)
            DailyNoteState.Ready(date, NoteWindows.slice(content, daily.row.viewLine))
        }
    }
}

@Composable
fun DailyNoteContent(state: DailyNoteState) {
    val context = LocalContext.current
    val open = WidgetIntents.daily(context, resume = true)
    Column(GlanceModifier.fillMaxSize().background(WidgetColors.bg).appWidgetBackground()) {
        if (state is DailyNoteState.SignedOut) {
            CenteredState(context.getString(R.string.widget_signed_out), WidgetIntents.app(context))
            return@Column
        }
        val date =
            when (state) {
                is DailyNoteState.Guard -> state.date
                is DailyNoteState.Missing -> state.date
                is DailyNoteState.Downloading -> state.date
                is DailyNoteState.Ready -> state.date
                DailyNoteState.SignedOut -> ""
            }
        Row(
            GlanceModifier
                .fillMaxWidth()
                .clickable(actionStartActivity(open))
                .padding(horizontal = NotesSpacing.space4, vertical = NotesSpacing.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TodayIcon()
            Spacer(GlanceModifier.width(NotesSpacing.space3))
            Text(WidgetText.label(date), style = WidgetText.header, maxLines = 1)
        }
        when (state) {
            is DailyNoteState.Ready -> NoteBody(state.window, open)
            is DailyNoteState.Missing -> StateLine(context.getString(R.string.widget_daily_missing), open)
            is DailyNoteState.Downloading -> StateLine(context.getString(R.string.widget_note_downloading), open)
            is DailyNoteState.Guard -> StateLine(context.getString(R.string.widget_daily_guard), open)
            DailyNoteState.SignedOut -> Unit
        }
    }
}

@Composable
private fun StateLine(
    text: String,
    open: android.content.Intent,
) {
    Text(
        text,
        GlanceModifier.fillMaxSize().clickable(actionStartActivity(open)).padding(horizontal = NotesSpacing.space4),
        style = WidgetText.state,
    )
}

/** The calendar-day icon in `accent`. */
@Composable
internal fun TodayIcon() {
    val context = LocalContext.current
    Image(
        provider = ImageProvider(R.drawable.ic_today),
        contentDescription = context.getString(R.string.widget_today_description),
        modifier = GlanceModifier.size(NotesSpacing.space6),
        colorFilter = ColorFilter.tint(WidgetColors.accent),
    )
}
