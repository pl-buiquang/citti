package app.citti.e2e.flows

import app.citti.core.fake.Endpoint
import app.citti.e2e.E2e
import app.citti.e2e.Smoke
import app.citti.e2e.robots.AgendaRobot
import app.citti.e2e.robots.ListRobot
import app.citti.e2e.robots.NoteRobot
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Agenda blocks (ADR 0012): the Event calendars are discovered on a tap with the Account's credentials, and a Note
 * asks for the events of its own block only while it is shown in Preview mode; the cache shows them offline.
 */
class AgendaTest {
    private val agendaNote = "```agenda\ndate: 2026-10-30\ndays: 2\n```\n"

    @get:Rule val e2e =
        E2eRules(
            Setup.signedInAndSynced(
                extra = {
                    E2e.server.addNote("Agenda", "# Agenda\n\n$agendaNote")
                    E2e.server.addCalendar(
                        "personal",
                        "Personal",
                        "#0082c9",
                        listOf(
                            event("standup", "Standup", "20261030T080000Z", "20261030T083000Z"),
                            event("talk", "Lunch talk", "20261030T140000Z", "20261030T150000Z"),
                            event("dentist", "Dentist", "20261031T090000Z", "20261031T100000Z"),
                        ),
                    )
                    E2e.server.addCalendar(
                        "work",
                        "Work",
                        "#ff0000",
                        listOf(event("review", "Work review", "20261030T100000Z", "20261030T110000Z")),
                    )
                },
            ),
        )

    private val list = ListRobot(e2e)
    private val note = NoteRobot(e2e)
    private val agenda = AgendaRobot(e2e)

    private fun event(
        uid: String,
        summary: String,
        start: String,
        end: String,
    ) = "BEGIN:VEVENT\nUID:$uid\nDTSTART:$start\nDTEND:$end\nSUMMARY:$summary\nEND:VEVENT"

    private val personal get() = E2e.server.calendarHref("personal")
    private val work get() = E2e.server.calendarHref("work")

    // Smoke: the cached events are @Serializable (a sealed EventTime), which R8 must keep.
    @Smoke
    @Test
    fun aBlockShowsTheEnabledCalendarsAndWorksOffline() {
        // Nothing asks for calendars until Discover is tapped.
        val settings = list.openSettings()
        assertEquals(0, E2e.server.count(Endpoint.CALDAV_PROPFIND))
        agenda.openSettings()
        agenda.discover()
        agenda.assertCalendarListed(personal, "Personal")
        agenda.assertCalendarListed(work, "Work")
        agenda.toggleCalendar(work)
        val propfind = E2e.server.requests.single { it.endpoint == Endpoint.CALDAV_PROPFIND }
        assertEquals("1", propfind.header("Depth"))
        assertTrue(propfind.header("Authorization")!!.startsWith("Basic "))
        settings.leave()

        list.openNote("Agenda")
        note.assertPreviewMode()
        // 13:00 in Paris: the 09:00 standup is over.
        agenda.assertEvent("Standup", dimmed = true)
        agenda.assertEvent("Lunch talk", dimmed = false)
        agenda.assertEvent("Dentist", dimmed = false)
        agenda.assertNoEvent("Work review")
        val reports = E2e.server.requests.filter { it.endpoint == Endpoint.CALDAV_REPORT }
        assertEquals(listOf(personal), reports.map { it.path })

        // Reopened at once: served from the cache.
        note.leave()
        list.openNote("Agenda")
        agenda.assertEvent("Lunch talk", dimmed = false)
        assertEquals(1, E2e.server.count(Endpoint.CALDAV_REPORT))

        // Stale and offline: the cached events stay, with the hint.
        note.leave()
        E2e.clock.advance(20.minutes)
        E2e.server.offline = true
        list.openNote("Agenda")
        agenda.assertStatus("Offline · updated")
        agenda.assertEvent("Lunch talk", dimmed = false)
        E2e.server.offline = false

        // The block is never rewritten: Edit mode shows it as typed, and so does the server.
        note.showEdit()
        assertTrue(agendaNote in note.editorText())
        assertTrue(
            agendaNote in
                E2e.server.notes
                    .single { it.title == "Agenda" }
                    .content,
        )
    }
}
