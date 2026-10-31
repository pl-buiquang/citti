package app.citti.ui.daily

import app.citti.core.daily.DailyEntry
import app.citti.core.sync.SyncStatus
import app.citti.ui.nav.Navigator
import app.citti.ui.nav.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.YearMonth
import kotlinx.datetime.yearMonth
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

data class CalendarUiState(
    /** The month shown; it lasts for the route entry. */
    val month: YearMonth,
    /** The device's local date, outlined in the grid; it moves at midnight. */
    val today: LocalDate,
    val weekStart: DayOfWeek,
    /** Every day with a Daily note (all months, so the pages next to [month] show their dots while swiping). */
    val noteDays: Set<LocalDate> = emptySet(),
    /** The Daily notes of [month], newest first. */
    val entries: List<DailyRow> = emptyList(),
    /** The day being opened or created: its cell shows a progress ring. */
    val busy: LocalDate? = null,
    /** A past day without a Daily note waiting for "Create daily note for 12 Oct?". */
    val confirm: LocalDate? = null,
    /** The Journal folder is an Unsynced folder: day taps create nothing (a snackbar leads to Settings). */
    val journalUnsynced: Boolean = false,
    /** The first sync of the Vault is running: the grid fills in as the notes arrive. */
    val firstSync: Boolean = false,
    /** True once the Daily notes of the Journal folder have loaded. */
    val loaded: Boolean = false,
)

/**
 * State holder of one Calendar route entry. The month comes from [route] (null: the current month); changing it
 * replaces the route ([Navigator.replaceTop]), so it survives process death and Back from a Note. [notes] are the
 * Daily notes of the Journal folder by date (from the local DB, Hidden ones included), [weekStartSetting] the Week
 * starts on setting (null follows [defaultWeekStart], the phone locale), [journalConflict] the Journal folder while
 * it is an Unsynced folder. [now] is the local date and time: today is read again at local midnight and when the app
 * returns to the foreground ([refreshToday]). Opening and creating go through the back stack's [today].
 */
class CalendarController(
    private val scope: CoroutineScope,
    notes: Flow<Map<LocalDate, DailyEntry>>,
    weekStartSetting: Flow<DayOfWeek?>,
    private val defaultWeekStart: () -> DayOfWeek,
    journalConflict: Flow<String?>,
    status: Flow<SyncStatus?>,
    private val today: TodayAction,
    private val navigator: Navigator,
    route: Route.Calendar,
    private val now: () -> LocalDateTime,
) {
    private val todayDate = MutableStateFlow(now().date)
    private val month = MutableStateFlow(route.month?.let(::parseMonth) ?: todayDate.value.yearMonth)
    private val byDate = MutableStateFlow<Map<LocalDate, DailyEntry>?>(null)

    init {
        scope.launch { notes.collect { byDate.value = it } }
        scope.launch {
            while (true) {
                delay(untilMidnight(now()))
                refreshToday()
            }
        }
    }

    private val dates: Flow<Dates> = combine(todayDate, month, ::Dates)

    private val week: Flow<DayOfWeek> = weekStartSetting.onStart { emit(null) }.map { it ?: defaultWeekStart() }.distinctUntilChanged()

    private val tap: Flow<Tap> = combine(today.busy, today.confirm, ::Tap)

    private val sync: Flow<Flags> =
        combine(journalConflict.onStart { emit(null) }, status.onStart { emit(null) }) { conflict, st ->
            Flags(journalUnsynced = conflict != null, firstSync = st is SyncStatus.Running && st.lastSuccessAt == null)
        }

    val state: StateFlow<CalendarUiState> =
        combine(dates, week, byDate, tap, sync) { d, weekStart, all, t, flags ->
            CalendarUiState(
                month = d.month,
                today = d.today,
                weekStart = weekStart,
                noteDays = all?.keys.orEmpty(),
                entries = all?.let { rowsOf(it, d.month) }.orEmpty(),
                busy = t.busy,
                confirm = t.confirm,
                journalUnsynced = flags.journalUnsynced,
                firstSync = flags.firstSync,
                loaded = all != null,
            )
        }.stateIn(scope, SharingStarted.Eagerly, CalendarUiState(month.value, todayDate.value, defaultWeekStart()))

    private data class Dates(
        val today: LocalDate,
        val month: YearMonth,
    )

    private data class Tap(
        val busy: LocalDate?,
        val confirm: LocalDate?,
    )

    private data class Flags(
        val journalUnsynced: Boolean,
        val firstSync: Boolean,
    )

    /** The arrows, a swipe of the grid, the Today chip: shows [m] and keeps it in the route. */
    fun showMonth(m: YearMonth) {
        if (month.value == m) return
        month.value = m
        if (navigator.top is Route.Calendar) navigator.replaceTop(Route.Calendar(m.toString()))
    }

    /** The Today chip: back to the current month. */
    fun backToToday() {
        refreshToday()
        showMonth(todayDate.value.yearMonth)
    }

    /**
     * A day of the grid: one with a Daily note opens it; today or a future day without one opens or creates it; a
     * past day without one asks first ([confirmCreate], [cancelCreate]). A tap while a day is being opened does
     * nothing. While the Journal folder is an Unsynced folder, a day without a note shows the snackbar at once.
     */
    fun onDay(d: LocalDate) {
        if (today.busy.value != null) return
        val entry = byDate.value?.get(d)
        if (entry != null) {
            navigator.push(Route.Note(entry.localId))
            return
        }
        today.open(d, confirmPast = !state.value.journalUnsynced)
    }

    /** A row under the grid. */
    fun onRow(row: DailyRow) = navigator.push(Route.Note(row.localId))

    /** "Create" in the past day dialog. */
    fun confirmCreate() = today.confirmCreate()

    /** "Cancel" in the past day dialog. */
    fun cancelCreate() = today.cancelCreate()

    /** Reads today again: the app is back in the foreground (the date may have changed meanwhile). */
    fun refreshToday() {
        todayDate.value = now().date
    }
}

private fun parseMonth(s: String): YearMonth? = runCatching { YearMonth.parse(s) }.getOrNull()

/** The Daily notes of [month], newest first. */
private fun rowsOf(
    all: Map<LocalDate, DailyEntry>,
    month: YearMonth,
): List<DailyRow> =
    all.entries
        .filter { it.key.yearMonth == month }
        .sortedByDescending { it.key }
        .map { (date, entry) -> DailyRow(date, entry.localId, entry.excerpt.orEmpty()) }

/**
 * The wait until the next local midnight after [now], capped at an hour so a change of time zone or a DST shift
 * delays the move by an hour at most.
 */
internal fun untilMidnight(now: LocalDateTime): Duration {
    val elapsed = now.hour.hours + now.minute.minutes + now.second.seconds + now.nanosecond.nanoseconds
    return (24.hours - elapsed).coerceIn(1.milliseconds, 1.hours)
}
