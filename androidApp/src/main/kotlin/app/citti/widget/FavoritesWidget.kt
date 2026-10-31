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
import app.citti.CittiApp
import app.citti.R
import app.citti.ui.theme.NotesSpacing
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** What the Favorites widget draws. */
sealed interface FavoritesState {
    data object SignedOut : FavoritesState

    /** [rows] are the favorites, most recently modified first. */
    data class Loaded(
        val rows: List<FavoriteRow>,
    ) : FavoritesState
}

class FavoritesWidget : GlanceAppWidget() {
    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val state = loadFavoritesState(context)
        provideContent { FavoritesContent(state) }
    }
}

class FavoritesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FavoritesWidget()
}

/** Reads the local DB only; never the network. */
internal suspend fun loadFavoritesState(context: Context): FavoritesState {
    val container = (context.applicationContext as CittiApp).container
    // Right after a cold start the stored credentials are still being read.
    val signedIn = withTimeoutOrNull(SIGNED_IN_WAIT_MS) { container.signedIn.filterNotNull().first() }
    if (signedIn != true) return FavoritesState.SignedOut
    return FavoritesState.Loaded(container.favoriteWidgetRows())
}

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
        val rows = (state as FavoritesState.Loaded).rows
        LazyColumn(GlanceModifier.fillMaxSize()) {
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

private const val EMPTY_ITEM_ID = -2L

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
