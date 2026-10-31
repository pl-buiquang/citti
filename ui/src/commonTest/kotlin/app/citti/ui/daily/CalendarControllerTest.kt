package app.citti.ui.daily

import app.citti.core.daily.DailyEntry
import app.citti.core.daily.DailyResult
import app.citti.core.sync.SyncPhase
import app.citti.core.sync.SyncStatus
import app.citti.ui.components.Snack
import app.citti.ui.nav.Navigator
import app.citti.ui.nav.Route
import app.citti.ui.nav.entryId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val CAL_TODAY = LocalDate(2026, 10, 31)

/** [DailyGateway] for the Calendar: [local] are the local Daily notes, [opened] what was opened or created. */
private class CalendarDaily(
    private val clock: () -> LocalDateTime,
) : DailyGateway {
    val local = mutableMapOf<LocalDate, Long>()
    var result: (LocalDate) -> DailyResult? = { DailyResult.Created(100, null) }
    var wait: CompletableDeferred<Unit>? = null
    val opened = mutableListOf<LocalDate>()

    override fun today(): LocalDate = clock().date

    override suspend fun localNote(date: LocalDate): Long? = local[date]

    override suspend fun openOrCreate(date: LocalDate): DailyResult? {
        opened += date
        wait?.await()
        return result(date)
    }

    override suspend fun dateOf(
        folder: String,
        title: String,
    ): LocalDate? = null
}

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarControllerTest {
    private class Fixture(
        val daily: CalendarDaily,
        val navigator: Navigator,
        val notes: MutableStateFlow<Map<LocalDate, DailyEntry>>,
        val weekStart: MutableStateFlow<DayOfWeek?>,
        val conflict: MutableStateFlow<String?>,
        val status: MutableStateFlow<SyncStatus?>,
        val snacks: MutableList<Snack>,
        val controller: CalendarController,
        /** Moves the clock without virtual time passing (the phone slept, the zone changed). */
        val jump: (Duration) -> Unit,
    ) {
        val state get() = controller.state.value
    }

    /** The clock starts at [start] (local time) and follows the virtual time of the test. */
    private fun TestScope.fixture(
        route: Route.Calendar = Route.Calendar(),
        start: LocalDateTime = LocalDateTime(2026, 10, 31, 14, 5),
        notes: Map<LocalDate, DailyEntry> = emptyMap(),
    ): Fixture {
        val origin = start.toInstant(TimeZone.UTC)
        var skew = Duration.ZERO
        val now = { (origin + testScheduler.currentTime.milliseconds + skew).toLocalDateTime(TimeZone.UTC) }
        val daily = CalendarDaily(now)
        val navigator = Navigator(listOf(route))
        val snacks = mutableListOf<Snack>()
        val today = TodayAction(backgroundScope, daily, navigator::push, { snacks += it })
        val notesFlow = MutableStateFlow(notes)
        val weekStart = MutableStateFlow<DayOfWeek?>(null)
        val conflict = MutableStateFlow<String?>(null)
        val status = MutableStateFlow<SyncStatus?>(null)
        val controller =
            CalendarController(
                scope = backgroundScope,
                notes = notesFlow,
                weekStartSetting = weekStart,
                defaultWeekStart = { DayOfWeek.MONDAY },
                journalConflict = conflict,
                status = status,
                today = today,
                navigator = navigator,
                route = route,
                now = now,
            )
        runCurrent()
        return Fixture(daily, navigator, notesFlow, weekStart, conflict, status, snacks, controller) { skew += it }
    }

    @Test
    fun theMonthComesFromTheRouteElseTheCurrentMonth() =
        runTest {
            assertEquals(YearMonth(2026, 8), fixture(Route.Calendar("2026-08")).state.month)
            assertEquals(YearMonth(2026, 10), fixture(Route.Calendar()).state.month)
            assertEquals(YearMonth(2026, 10), fixture(Route.Calendar("garbage")).state.month)
            assertEquals(CAL_TODAY, fixture().state.today)
        }

    @Test
    fun aMonthChangeReplacesTheRouteAndKeepsTheEntry() =
        runTest {
            val f = fixture()
            f.controller.showMonth(YearMonth(2026, 9))
            runCurrent()
            assertEquals(YearMonth(2026, 9), f.state.month)
            assertEquals(listOf<Route>(Route.Calendar("2026-09")), f.navigator.stack.value)
            assertEquals(Route.Calendar().entryId, f.navigator.top.entryId)

            // Back from a Note returns to the Calendar on the same month.
            f.navigator.push(Route.Note(3))
            f.navigator.pop()
            assertEquals(Route.Calendar("2026-09"), f.navigator.top)
        }

    @Test
    fun theTodayChipGoesBackToTheCurrentMonth() =
        runTest {
            val f = fixture(Route.Calendar("2025-03"))
            f.controller.backToToday()
            runCurrent()
            assertEquals(YearMonth(2026, 10), f.state.month)
            assertEquals(Route.Calendar("2026-10"), f.navigator.top)
        }

    @Test
    fun theWeekStartFollowsTheSettingElseThePhone() =
        runTest {
            val f = fixture()
            assertEquals(DayOfWeek.MONDAY, f.state.weekStart)
            f.weekStart.value = DayOfWeek.SUNDAY
            runCurrent()
            assertEquals(DayOfWeek.SUNDAY, f.state.weekStart)
            f.weekStart.value = null
            runCurrent()
            assertEquals(DayOfWeek.MONDAY, f.state.weekStart)
        }

    @Test
    fun theMonthsDailyNotesNewestFirstAndEveryDotOfTheIndex() =
        runTest {
            val notes =
                mapOf(
                    LocalDate(2026, 10, 3) to DailyEntry(3, "third"),
                    LocalDate(2026, 10, 29) to DailyEntry(29, null),
                    LocalDate(2026, 9, 30) to DailyEntry(30, "september"),
                )
            val f = fixture(notes = notes)
            assertTrue(f.state.loaded)
            assertEquals(notes.keys, f.state.noteDays)
            assertEquals(
                listOf(DailyRow(LocalDate(2026, 10, 29), 29, ""), DailyRow(LocalDate(2026, 10, 3), 3, "third")),
                f.state.entries,
            )

            f.notes.value = notes + (LocalDate(2026, 10, 31) to DailyEntry(31, "today"))
            runCurrent()
            assertEquals(listOf(31L, 29L, 3L), f.state.entries.map { it.localId })

            f.controller.showMonth(YearMonth(2026, 11))
            runCurrent()
            assertEquals(emptyList(), f.state.entries)
        }

    @Test
    fun aDayWithANoteOpensIt() =
        runTest {
            val f = fixture(notes = mapOf(LocalDate(2026, 10, 12) to DailyEntry(12, "x")))
            f.controller.onDay(LocalDate(2026, 10, 12))
            runCurrent()
            assertEquals(Route.Note(12), f.navigator.top)
            assertTrue(f.daily.opened.isEmpty())
        }

    @Test
    fun todayAndAFutureDayWithoutANoteOpenOrCreate() =
        runTest {
            val f = fixture()
            f.controller.onDay(CAL_TODAY)
            runCurrent()
            assertEquals(Route.Note(100, focus = true), f.navigator.top)
            f.navigator.pop()
            f.controller.onDay(LocalDate(2026, 11, 4))
            runCurrent()
            assertEquals(listOf(CAL_TODAY, LocalDate(2026, 11, 4)), f.daily.opened)
            assertNull(f.state.confirm)
        }

    @Test
    fun aPastDayWithoutANoteAsksFirst() =
        runTest {
            val past = LocalDate(2026, 10, 12)
            val f = fixture()
            f.controller.onDay(past)
            runCurrent()
            assertEquals(past, f.state.confirm)
            assertTrue(f.daily.opened.isEmpty())

            f.controller.cancelCreate()
            runCurrent()
            assertNull(f.state.confirm)
            assertTrue(f.daily.opened.isEmpty())

            f.controller.onDay(past)
            runCurrent()
            f.controller.confirmCreate()
            runCurrent()
            assertNull(f.state.confirm)
            assertEquals(listOf(past), f.daily.opened)
            assertEquals(Route.Note(100, focus = true), f.navigator.top)
        }

    @Test
    fun theBusyDayBlocksASecondTap() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val f = fixture(notes = mapOf(LocalDate(2026, 10, 12) to DailyEntry(12, "x")))
            f.daily.wait = gate
            f.controller.onDay(CAL_TODAY)
            runCurrent()
            assertEquals(CAL_TODAY, f.state.busy)

            f.controller.onDay(LocalDate(2026, 11, 2))
            f.controller.onDay(LocalDate(2026, 10, 12))
            runCurrent()
            assertEquals(listOf(CAL_TODAY), f.daily.opened)
            assertEquals(listOf<Route>(Route.Calendar()), f.navigator.stack.value)

            gate.complete(Unit)
            runCurrent()
            assertNull(f.state.busy)
            assertEquals(Route.Note(100, focus = true), f.navigator.top)
        }

    @Test
    fun anUnsyncedJournalFolderShowsTheSnackbarWithoutAsking() =
        runTest {
            val f = fixture()
            f.daily.result = { DailyResult.JournalUnsynced }
            f.conflict.value = "journal"
            runCurrent()
            assertTrue(f.state.journalUnsynced)
            f.controller.onDay(LocalDate(2026, 10, 12))
            runCurrent()
            assertNull(f.state.confirm)
            assertEquals(listOf<Snack>(Snack.JournalUnsynced), f.snacks)
            assertEquals(listOf<Route>(Route.Calendar()), f.navigator.stack.value)
        }

    @Test
    fun todayMovesAtLocalMidnight() =
        runTest {
            val f = fixture(start = LocalDateTime(2026, 10, 31, 23, 59, 30))
            assertEquals(CAL_TODAY, f.state.today)
            advanceTimeBy(29.seconds)
            runCurrent()
            assertEquals(CAL_TODAY, f.state.today)
            advanceTimeBy(1.seconds + 1.milliseconds)
            runCurrent()
            assertEquals(LocalDate(2026, 11, 1), f.state.today)
            // The month shown lasts for the route entry.
            assertEquals(YearMonth(2026, 10), f.state.month)
        }

    @Test
    fun todayIsReadAgainInTheForeground() =
        runTest {
            val f = fixture(start = LocalDateTime(2026, 10, 31, 22, 0))
            // The timer waits until 23:00; the clock reaches the next day before it fires.
            f.jump(3.hours)
            runCurrent()
            assertEquals(CAL_TODAY, f.state.today)
            f.controller.refreshToday()
            runCurrent()
            assertEquals(LocalDate(2026, 11, 1), f.state.today)
        }

    @Test
    fun theFirstSyncFlag() =
        runTest {
            val f = fixture()
            assertFalse(f.state.firstSync)
            f.status.value = SyncStatus.Running(SyncPhase.Pulling, null, lastSuccessAt = null)
            runCurrent()
            assertTrue(f.state.firstSync)
            f.status.value = SyncStatus.Running(SyncPhase.Pulling, null, lastSuccessAt = 100)
            runCurrent()
            assertFalse(f.state.firstSync)
        }

    @Test
    fun untilMidnightIsCappedAtAnHour() {
        assertEquals(30.seconds, untilMidnight(LocalDateTime(2026, 10, 31, 23, 59, 30)))
        assertEquals(1.hours, untilMidnight(LocalDateTime(2026, 10, 31, 14, 5)))
        assertEquals(1.minutes, untilMidnight(LocalDateTime(2026, 10, 31, 23, 59)))
    }
}
