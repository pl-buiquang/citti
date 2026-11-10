package app.citti.ui.daily

import app.citti.core.daily.DailyResult
import app.citti.core.daily.Period
import app.citti.core.daily.PeriodKind
import app.citti.core.note.NameError
import app.citti.ui.components.DroppedDaily
import app.citti.ui.components.Snack
import app.citti.ui.nav.Navigator
import app.citti.ui.nav.NoteNotice
import app.citti.ui.nav.Route
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val TODAY = LocalDate(2026, 10, 31)

private class FakeDaily : DailyGateway {
    var today = TODAY
    val local = mutableMapOf<LocalDate, Long>()
    var result: (LocalDate) -> DailyResult? = { DailyResult.Opened(1) }
    var wait: CompletableDeferred<Unit>? = null
    val opened = mutableListOf<LocalDate>()
    val dailyDates = mutableMapOf<Pair<String, String>, LocalDate>()
    val viewLines = mutableMapOf<Long, Int>()

    override fun today(): LocalDate = today

    override suspend fun localNote(period: Period): Long? = local[period.start]

    override suspend fun openOrCreate(period: Period): DailyResult? {
        opened += period.start
        wait?.await()
        return result(period.start)
    }

    override suspend fun viewLine(localId: Long): Int? = viewLines[localId]

    override suspend fun dateOf(
        folder: String,
        title: String,
    ): LocalDate? = dailyDates[folder to title]
}

private object Labels : PhoneCalendarLabels {
    override fun monthYear(month: YearMonth) = "$month"

    override fun monthName(month: YearMonth) = "$month"

    override fun weekdayInitial(day: DayOfWeek) = day.name.take(1)

    override fun weekdayName(day: DayOfWeek) = day.name

    override fun defaultWeekStart() = DayOfWeek.MONDAY

    override fun shortDate(date: LocalDate) = "$date"

    override fun dayMonth(date: LocalDate) = "${date.day} ${date.month.name.take(3).lowercase()}"

    override fun dayMonthLong(date: LocalDate) = "$date"
}

@OptIn(ExperimentalCoroutinesApi::class)
class TodayActionTest {
    private class Fixture(
        val daily: FakeDaily,
        val navigator: Navigator,
        val snacks: MutableList<Snack>,
        val action: TodayAction,
    )

    private fun TestScope.fixture(setup: FakeDaily.() -> Unit = {}): Fixture {
        val daily = FakeDaily().apply(setup)
        val navigator = Navigator(listOf(Route.List()))
        val snacks = mutableListOf<Snack>()
        return Fixture(
            daily,
            navigator,
            snacks,
            TodayAction(backgroundScope, daily, navigator::push, { snacks += it }) { navigator.reset(listOf(it)) },
        )
    }

    @Test
    fun anExistingNoteOpensWithoutFocus() =
        runTest {
            val f = fixture { result = { DailyResult.Opened(5) } }
            f.action.openToday()
            runCurrent()
            assertEquals(listOf(TODAY), f.daily.opened)
            assertEquals(Route.Note(5), f.navigator.top)
            assertNull(f.action.busy.value)
        }

    @Test
    fun anEntryOpensTheNoteAsTheWholeBackStack() =
        runTest {
            val f = fixture { result = { DailyResult.Opened(5) } }
            f.action.openToday(entry = true)
            runCurrent()
            assertEquals(listOf<Route>(Route.Note(5)), f.navigator.stack.value)
        }

    @Test
    fun resumeOpensAnExistingNoteAtItsReadingPosition() =
        runTest {
            val f =
                fixture {
                    result = { DailyResult.Opened(5) }
                    viewLines[5] = 42
                }
            f.action.openToday(resume = true)
            runCurrent()
            assertEquals(Route.Note(5, startLine = 42), f.navigator.top)
        }

    @Test
    fun resumeWithoutAReadingPositionKeepsThePlainOpen() =
        runTest {
            val f = fixture { result = { DailyResult.Opened(5) } }
            f.action.openToday(resume = true)
            runCurrent()
            assertEquals(Route.Note(5), f.navigator.top)
        }

    @Test
    fun withoutResumeTheReadingPositionIsIgnored() =
        runTest {
            val f =
                fixture {
                    result = { DailyResult.Opened(5) }
                    viewLines[5] = 42
                }
            f.action.openToday()
            runCurrent()
            assertEquals(Route.Note(5), f.navigator.top)
        }

    @Test
    fun aCreatedNoteOpensFocusedWhateverResume() =
        runTest {
            val f =
                fixture {
                    result = { DailyResult.Created(8, templateMissing = null) }
                    viewLines[8] = 3
                }
            f.action.openToday(resume = true)
            runCurrent()
            assertEquals(Route.Note(8, preview = false, focus = true), f.navigator.top)
        }

    @Test
    fun aCreatedNoteOpensFocused() =
        runTest {
            val f = fixture { result = { DailyResult.Created(8, templateMissing = null) } }
            f.action.openToday()
            runCurrent()
            assertEquals(Route.Note(8, preview = false, focus = true), f.navigator.top)
        }

    @Test
    fun aMissingTemplateGoesInTheRouteNotice() =
        runTest {
            val f = fixture { result = { DailyResult.Created(8, templateMissing = "templates/daily.md") } }
            f.action.openToday()
            runCurrent()
            assertEquals(
                Route.Note(8, preview = false, focus = true, notice = NoteNotice.TemplateMissing("templates/daily.md")),
                f.navigator.top,
            )
        }

    @Test
    fun anUnsyncedJournalFolderShowsTheSnackbarAndOpensNothing() =
        runTest {
            val f = fixture { result = { DailyResult.JournalUnsynced } }
            f.action.openToday()
            runCurrent()
            assertEquals(listOf<Snack>(Snack.JournalUnsynced), f.snacks)
            assertEquals(listOf<Route>(Route.List()), f.navigator.stack.value)
        }

    @Test
    fun signedOutOrAnInvalidTitleOpensNothing() =
        runTest {
            val f = fixture { result = { null } }
            f.action.openToday()
            runCurrent()
            f.daily.result = { DailyResult.InvalidTitle("a/b", NameError.Slash) }
            f.action.openToday()
            runCurrent()
            assertEquals(listOf<Route>(Route.List()), f.navigator.stack.value)
            assertTrue(f.snacks.isEmpty())
        }

    @Test
    fun aSecondTapWhileBusyDoesNothing() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val f =
                fixture {
                    wait = gate
                    result = { DailyResult.Created(8, null) }
                }
            f.action.openToday()
            runCurrent()
            assertEquals(Period.of(PeriodKind.DAY, TODAY), f.action.busy.value)
            f.action.openToday()
            f.action.open(LocalDate(2026, 11, 2))
            runCurrent()
            assertEquals(listOf(TODAY), f.daily.opened)

            gate.complete(Unit)
            runCurrent()
            assertNull(f.action.busy.value)
            assertEquals(listOf(Route.List(), Route.Note(8, preview = false, focus = true)), f.navigator.stack.value)
        }

    @Test
    fun todayIsReadAtEachTap() =
        runTest {
            val f = fixture()
            f.action.openToday()
            runCurrent()
            f.daily.today = LocalDate(2026, 11, 1)
            f.action.openToday()
            runCurrent()
            assertEquals(listOf(TODAY, LocalDate(2026, 11, 1)), f.daily.opened)
        }

    @Test
    fun aPastDayWithoutANoteWaitsForConfirmation() =
        runTest {
            val past = LocalDate(2026, 10, 12)
            val f = fixture { result = { DailyResult.Created(9, null) } }
            f.action.open(past, confirmPast = true)
            runCurrent()
            assertEquals(Period.of(PeriodKind.DAY, past), f.action.confirm.value)
            assertTrue(f.daily.opened.isEmpty())
            assertNull(f.action.busy.value)

            f.action.cancelCreate()
            assertNull(f.action.confirm.value)

            f.action.open(past, confirmPast = true)
            runCurrent()
            f.action.confirmCreate()
            runCurrent()
            assertNull(f.action.confirm.value)
            assertEquals(listOf(past), f.daily.opened)
            assertEquals(Route.Note(9, preview = false, focus = true), f.navigator.top)
        }

    @Test
    fun aPastDayWithANoteTodayAndTheFutureNeedNoConfirmation() =
        runTest {
            val past = LocalDate(2026, 10, 12)
            val f = fixture { local[past] = 4 }
            f.action.open(past, confirmPast = true)
            runCurrent()
            f.action.open(TODAY, confirmPast = true)
            runCurrent()
            f.action.open(LocalDate(2026, 11, 3), confirmPast = true)
            runCurrent()
            assertNull(f.action.confirm.value)
            assertEquals(listOf(past, TODAY, LocalDate(2026, 11, 3)), f.daily.opened)
        }

    @Test
    fun theDroppedWordingNamesTodayOrTheDay() =
        runTest {
            val daily =
                FakeDaily().apply {
                    dailyDates["journal" to "2026-10-31"] = TODAY
                    dailyDates["journal" to "2026-10-12"] = LocalDate(2026, 10, 12)
                }
            assertEquals(DroppedDaily.Today, daily.droppedDaily("journal", "2026-10-31", Labels))
            assertEquals(DroppedDaily.Day("12 oct"), daily.droppedDaily("journal", "2026-10-12", Labels))
            assertNull(daily.droppedDaily("work", "Plan", Labels))
        }
}
