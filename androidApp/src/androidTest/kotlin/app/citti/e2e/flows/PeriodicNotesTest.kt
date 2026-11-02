package app.citti.e2e.flows

import app.citti.core.fake.Endpoint
import app.citti.e2e.E2e
import app.citti.e2e.ServerAssert
import app.citti.e2e.robots.ListRobot
import app.citti.e2e.robots.NoteRobot
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import kotlinx.datetime.DayOfWeek
import org.junit.Rule
import org.junit.Test

/**
 * The Week, Month, Quarter and Year notes, opened from the Calendar: the month and the year of the header, the week
 * numbers and a long press on a day, and the quarter chip (the last two are off until Settings turns them on). The
 * Journal folder is Hidden: the Calendar still finds the notes. The journal is written in French, as the vault's.
 */
class PeriodicNotesTest {
    @get:Rule val e2e =
        E2eRules(
            Setup.signedInAndSynced(
                hidden = listOf("archive", "journal"),
                extra = {
                    E2e.server.addNote("2026-octobre", "# Octobre\n", category = "journal")
                    E2e.server.addNote("2026-W44", "# Semaine 44\n", category = "journal")
                },
            ),
        )

    private val list = ListRobot(e2e)
    private val note = NoteRobot(e2e)
    private val server = ServerAssert()

    @Test
    fun theMonthOpensItsNoteAndTheYearCreatesOne() {
        val calendar = list.showCalendar()
        E2e.server.clearRequests()

        calendar.tapMonth()
        note.assertTitle("2026-octobre")
        server.assertRequests(Endpoint.CREATE, 0)
        note.leave()

        // The current year has no note yet and is not over: no question asked.
        calendar.tapYear()
        note.assertTitle("2026")
        server.awaitRequest(Endpoint.CREATE)
        server.assertNote("2026", category = "journal")
    }

    @Test
    fun aPastMonthWithoutANoteAsksFirst() {
        val calendar = list.showCalendar()
        calendar.previousMonth()
        calendar.assertMonth("September 2026")
        E2e.server.clearRequests()

        calendar.tapMonth()
        calendar.assertCreatePast("Create month note for September 2026?")
        server.assertRequests(Endpoint.CREATE, 0)
        calendar.confirmCreate()

        note.assertTitle("2026-septembre")
        server.awaitRequest(Endpoint.CREATE)
        server.assertNote("2026-septembre", category = "journal")
    }

    @Test
    fun weekNumbersAreOffUntilSettingsShowThem() {
        E2e.container.dailySettings.setWeekStart(DayOfWeek.MONDAY)
        val calendar = list.showCalendar()
        calendar.assertNoWeekNumbers("2026-10-26")

        E2e.container.dailySettings.setShowWeeks(true)
        calendar.tapWeek("2026-10-26")
        note.assertTitle("2026-W44")
    }

    @Test
    fun aLongPressOnADayOpensItsWeekEvenWithoutTheWeekNumbers() {
        E2e.container.dailySettings.setWeekStart(DayOfWeek.MONDAY)
        val calendar = list.showCalendar()
        calendar.assertNoWeekNumbers("2026-10-26")

        calendar.longPressDay("29 October")
        note.assertTitle("2026-W44")
        note.leave()

        // Week 42 is over and has no note.
        calendar.longPressDay("14 October")
        calendar.assertCreatePast("Create week note for week 42?")
    }

    @Test
    fun theQuarterChipIsOffUntilSettingsShowsIt() {
        val calendar = list.showCalendar()
        calendar.assertNoQuarterChip()

        E2e.container.dailySettings.setShowQuarter(true)
        calendar.tapQuarter("Q4")

        note.assertTitle("2026-Q4")
        server.awaitRequest(Endpoint.CREATE)
        server.assertNote("2026-Q4", category = "journal")
    }
}
