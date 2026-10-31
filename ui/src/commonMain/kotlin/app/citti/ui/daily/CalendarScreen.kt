package app.citti.ui.daily

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.citti.core.settings.MainMode
import app.citti.ui.TestTags
import app.citti.ui.browse.BrowseActions
import app.citti.ui.browse.BrowseUiState
import app.citti.ui.browse.MainScaffold
import app.citti.ui.browse.rememberNow
import app.citti.ui.components.CittiPullRefresh
import app.citti.ui.components.EmptyState
import app.citti.ui.resources.Res
import app.citti.ui.resources.empty_list_first_sync
import app.citti.ui.theme.NotesSpacing
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.yearMonth
import org.jetbrains.compose.resources.stringResource

/** The user's actions on the Calendar body; the scaffold's (search, ModeSwitch, buttons) are in [BrowseActions]. */
class CalendarActions(
    val onMonthChange: (YearMonth) -> Unit,
    val onDay: (LocalDate) -> Unit,
    val onRow: (DailyRow) -> Unit,
    val onConfirmCreate: () -> Unit,
    val onCancelCreate: () -> Unit,
)

/**
 * The Calendar mode ([design-brief] §17) inside the main view's scaffold: the search bar, the ModeSwitch, the banner
 * (the first-sync progress line while the Vault arrives), then the [CalendarHeader], the [CalendarGrid] and the
 * [DailyList] in one scroll, and the Today and New note buttons. Pulling down runs a full Sync, as in List. [browse]
 * gives the banner and the sync state, [state] the Calendar's own.
 */
@Composable
fun CalendarScreen(
    state: CalendarUiState,
    browse: BrowseUiState,
    labels: PhoneCalendarLabels,
    nowSeconds: () -> Long,
    actions: BrowseActions,
    calendar: CalendarActions,
    newNoteBusy: Boolean = false,
    todayBusy: Boolean = false,
) {
    val now = rememberNow(nowSeconds)
    MainScaffold(
        mode = MainMode.CALENDAR,
        banner = browse.banner,
        now = now,
        actions = actions,
        newNoteBusy = newNoteBusy,
        todayBusy = todayBusy,
    ) { fabRoom ->
        CittiPullRefresh(refreshing = browse.syncing, onRefresh = actions.onSyncNow, modifier = Modifier.weight(1f)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .testTag(TestTags.CALENDAR_BODY)
                    .verticalScroll(rememberScrollState())
                    .padding(fabRoom),
            ) {
                CalendarHeader(state.month, state.today.yearMonth, labels, calendar.onMonthChange)
                CalendarGrid(
                    month = state.month,
                    today = state.today,
                    weekStart = state.weekStart,
                    noteDays = state.noteDays,
                    labels = labels,
                    onMonthChange = calendar.onMonthChange,
                    onDay = calendar.onDay,
                    busy = state.busy,
                )
                when {
                    !state.loaded -> {
                        Unit
                    }

                    // The Journal folder is still on its way: the grid fills in as its notes arrive.
                    state.entries.isEmpty() && state.firstSync -> {
                        EmptyState(
                            stringResource(Res.string.empty_list_first_sync),
                            Modifier.fillMaxWidth().padding(vertical = NotesSpacing.space8),
                        )
                    }

                    else -> {
                        DailyList(state.month, state.entries, labels, calendar.onRow, Modifier.padding(top = NotesSpacing.space2))
                    }
                }
            }
        }
    }
    state.confirm?.let { date -> CreatePastDayDialog(date, labels, calendar.onConfirmCreate, calendar.onCancelCreate) }
}
