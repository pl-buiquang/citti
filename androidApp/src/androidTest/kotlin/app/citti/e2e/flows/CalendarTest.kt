package app.citti.e2e.flows

import app.citti.core.fake.Endpoint
import app.citti.e2e.E2e
import app.citti.e2e.ServerAssert
import app.citti.e2e.robots.ListRobot
import app.citti.e2e.robots.NoteRobot
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import org.junit.Rule
import org.junit.Test

/**
 * Calendar mode with the Journal folder Hidden: its notes leave List mode but the Calendar still marks their days
 * (Hidden is filtered per query, never for the Calendar). A day with a note opens it; a past day without one asks first.
 */
class CalendarTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced(hidden = listOf("archive", "journal")))

    private val list = ListRobot(e2e)
    private val note = NoteRobot(e2e)
    private val server = ServerAssert()

    @Test
    fun marksTheDaysOfAHiddenJournalAndOpensThem() {
        list.assertRow("Ideas")
        list.assertNoRow("2026-10-30")
        E2e.server.clearRequests()
        val calendar = list.showCalendar()
        calendar.assertMonth("October 2026")
        calendar.assertNoteDay("29 October")
        calendar.assertNoteDay("30 October")
        calendar.assertEmptyDay("28 October")

        calendar.previousMonth()
        calendar.assertMonth("September 2026")
        calendar.assertNoteDay("15 September")
        calendar.assertEmptyDay("16 September")
        calendar.nextMonth()
        calendar.assertMonth("October 2026")

        server.assertNoRequests()
        calendar.tapDay("29 October")
        note.assertTitle("2026-10-29")
        note.assertText("jeudi 29 octobre 2026")
        server.assertRequests(Endpoint.CREATE, 0)
    }

    @Test
    fun theDailyBarStepsOverDaysWithoutANote() {
        val calendar = list.showCalendar()
        calendar.tapDay("30 October")
        note.assertTitle("2026-10-30")
        note.assertNoNextDaily()
        note.openPreviousDaily("2026-10-29")
        note.assertTitle("2026-10-29")
        // The days between 2026-09-15 and 2026-10-29 have no note: the bar skips them.
        note.openPreviousDaily("2026-09-15")
        note.assertTitle("2026-09-15")
        note.openNextDaily("2026-10-29")
        note.assertTitle("2026-10-29")
        // Back leaves the Daily notes: the entry was replaced, not stacked.
        note.pressBack()
        calendar.assertShown()
    }

    @Test
    fun pastDayWithoutANoteIsCreatedAfterAsking() {
        val calendar = list.showCalendar()
        calendar.tapDay("28 October")
        calendar.assertCreatePastDay("28 Oct")
        server.assertRequests(Endpoint.CREATE, 0)
        calendar.confirmCreate()

        note.assertTitle("2026-10-28")
        note.assertText("# mercredi 28 octobre 2026")
        server.awaitRequest(Endpoint.CREATE) { "2026-10-28" in it.body }
        server.assertNote("2026-10-28", category = "journal", content = "# mercredi 28 octobre 2026\n\nWeek 44\n\n## Notes\n\n")
        note.leave()
        calendar.assertNoteDay("28 October")
    }
}
