package app.citti.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import app.citti.AndroidSyncLog
import app.citti.AppContainer
import app.citti.CittiApp
import app.citti.JavaTimeRowDateFormatter
import app.citti.R
import app.citti.ui.components.colored
import app.citti.ui.search.SearchActions
import app.citti.ui.search.SearchController
import app.citti.ui.search.SearchScreen
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSpacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The widget's `android:configure` Activity: picks the Note of a Note widget when it is placed or reconfigured. It is
 * Search over every synced Note (Hidden folders included); with an empty query it lists the Notes, favorites first.
 */
class NotePickerActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val container: AppContainer get() = (application as CittiApp).container
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Back (or any early exit) cancels, and Android then does not place a new widget.
        setResult(RESULT_CANCELED, result())
        // Exported for the launcher: any app can start it, so it only serves a placed Note widget of ours.
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || !isNoteWidget(appWidgetId)) {
            finish()
            return
        }
        setContent { CittiTheme { Content() } }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun isNoteWidget(id: Int): Boolean =
        AppWidgetManager
            .getInstance(this)
            .getAppWidgetInfo(id)
            ?.provider
            ?.className == NoteWidgetReceiver::class.java.name

    private fun result() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

    @Composable
    private fun Content() {
        val signedIn by container.signedIn.collectAsState()
        Column(Modifier.fillMaxSize().background(CittiTheme.colors.bg).safeDrawingPadding()) {
            BasicText(
                getString(R.string.picker_title),
                modifier = Modifier.padding(horizontal = NotesSpacing.space4, vertical = NotesSpacing.space3),
                style = CittiTheme.typography.rowTitle.colored(muted = false),
            )
            when (signedIn) {
                true -> Picker()
                false -> SignedOut()
                null -> Unit
            }
        }
    }

    @Composable
    private fun SignedOut() {
        BasicText(
            getString(R.string.picker_signed_out),
            modifier = Modifier.padding(NotesSpacing.space4),
            style = CittiTheme.typography.body.colored(muted = true),
        )
        BasicText(
            getString(R.string.picker_open_app),
            modifier =
                Modifier
                    .padding(NotesSpacing.space4)
                    .clickable {
                        startActivity(WidgetIntents.app(this@NotePickerActivity))
                        finish()
                    },
            style = CittiTheme.typography.ui.colored(muted = false),
        )
    }

    @Composable
    private fun Picker() {
        val controller =
            remember {
                SearchController(
                    scope = scope,
                    hits = container.noteSearch::hits,
                    snippets = container.noteSearch::snippets,
                    initialQuery = "",
                    log = AndroidSyncLog,
                )
            }
        val state by controller.state.collectAsState()
        val rows by remember { container.listRows(favoritesOnly = false) }.collectAsState(emptyList())
        val dates = remember { JavaTimeRowDateFormatter(zone = container.config.timeZone) }
        SearchScreen(
            state = state,
            nowSeconds = { container.config.nowSeconds() },
            dates = dates,
            listState = rememberLazyListState(),
            actions =
                SearchActions(
                    onQuery = controller::onQuery,
                    onClear = controller::clear,
                    onVisible = controller::onVisible,
                    onOpenNote = ::pick,
                    onBack = ::finish,
                ),
            idleRows = rows,
        )
    }

    private fun pick(localId: Long) {
        scope.launch {
            val glanceId = GlanceAppWidgetManager(this@NotePickerActivity).getGlanceIdBy(appWidgetId)
            updateAppWidgetState(this@NotePickerActivity, glanceId) { it[NOTE_WIDGET_LOCAL_ID] = localId }
            NoteWidget().update(this@NotePickerActivity, glanceId)
            setResult(RESULT_OK, result())
            finish()
        }
    }
}
