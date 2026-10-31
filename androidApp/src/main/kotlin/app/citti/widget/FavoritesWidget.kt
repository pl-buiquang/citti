package app.citti.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import app.citti.AppContainer
import app.citti.CittiApp
import app.citti.R
import app.citti.ui.theme.NotesSpacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the Favorites widget draws. */
sealed interface FavoritesState {
    data object SignedOut : FavoritesState

    /** [rows] are the favorites, most recently modified first; [today] is the date under the pinned Today row. */
    data class Loaded(
        val rows: List<FavoriteRow>,
        val today: String,
    ) : FavoritesState
}

class FavoritesWidget : GlanceAppWidget() {
    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        DayScheduler.schedule(context)
        val state = loadFavoritesState(context)
        provideContent { FavoritesContent(state) }
    }
}

class FavoritesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FavoritesWidget()

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

/** Reads the local DB only; never the network. */
internal suspend fun loadFavoritesState(context: Context): FavoritesState {
    if (!isSignedIn(context)) return FavoritesState.SignedOut
    return FavoritesState.Loaded(context.container().favoriteWidgetRows(), context.todayLabel())
}

internal fun Context.container(): AppContainer = (applicationContext as CittiApp).container

/** Right after a cold start the stored credentials are still being read, so wait for them briefly. */
internal suspend fun isSignedIn(context: Context): Boolean =
    withTimeoutOrNull(SIGNED_IN_WAIT_MS) {
        context
            .container()
            .signedIn
            .filterNotNull()
            .first()
    } == true

internal const val SIGNED_IN_WAIT_MS = 5_000L

@Composable
fun FavoritesContent(state: FavoritesState) {
    val context = LocalContext.current
    Column(GlanceModifier.fillMaxSize().background(WidgetColors.bg).appWidgetBackground()) {
        if (state is FavoritesState.SignedOut) {
            Box(
                GlanceModifier.fillMaxSize().clickable(actionStartActivity(WidgetIntents.app(context))),
                contentAlignment = Alignment.Center,
            ) { Text(context.getString(R.string.widget_signed_out), style = WidgetText.state) }
            return@Column
        }
        Row(
            GlanceModifier
                .fillMaxWidth()
                .clickable(actionStartActivity(WidgetIntents.app(context)))
                .padding(horizontal = NotesSpacing.space4, vertical = NotesSpacing.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("★", style = WidgetText.star)
            Spacer(GlanceModifier.width(NotesSpacing.space2))
            Text(context.getString(R.string.widget_favorites_name), style = WidgetText.header)
        }
        val loaded = state as FavoritesState.Loaded
        val rows = loaded.rows
        LazyColumn(GlanceModifier.fillMaxSize()) {
            // Always first, whether or not today's note exists: the app opens or creates it.
            item(itemId = TODAY_ITEM_ID) { TodayRowContent(loaded.today) }
            if (rows.isEmpty()) {
                item(itemId = EMPTY_ITEM_ID) {
                    Text(
                        context.getString(R.string.widget_favorites_empty),
                        GlanceModifier.padding(horizontal = NotesSpacing.space4, vertical = NotesSpacing.space2),
                        style = WidgetText.state,
                    )
                }
            }
            items(rows, itemId = { it.localId }) { row -> FavoriteRowContent(row) }
        }
    }
}

private const val TODAY_ITEM_ID = -1L
private const val EMPTY_ITEM_ID = -2L

@Composable
private fun TodayRowContent(date: String) {
    val context = LocalContext.current
    Row(
        GlanceModifier
            .fillMaxWidth()
            .clickable(actionStartActivity(WidgetIntents.daily(context, resume = false)))
            .padding(horizontal = NotesSpacing.space4, vertical = NotesSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TodayIcon()
        Spacer(GlanceModifier.width(NotesSpacing.space3))
        Column {
            Text(context.getString(R.string.widget_today_row), style = WidgetText.title, maxLines = 1)
            Text(date, style = WidgetText.folder, maxLines = 1)
        }
    }
}

@Composable
private fun FavoriteRowContent(row: FavoriteRow) {
    val context = LocalContext.current
    Column(
        GlanceModifier
            .fillMaxWidth()
            .clickable(actionStartActivity(WidgetIntents.note(context, row.localId)))
            .padding(horizontal = NotesSpacing.space4, vertical = NotesSpacing.space2),
    ) {
        Text(row.title, style = WidgetText.title, maxLines = 1)
        Text(row.category.ifEmpty { context.getString(R.string.widget_root_folder) }, style = WidgetText.folder, maxLines = 1)
    }
}
