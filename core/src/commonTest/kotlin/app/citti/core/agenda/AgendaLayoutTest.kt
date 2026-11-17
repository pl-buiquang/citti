package app.citti.core.agenda

import app.citti.core.daily.DateLocales
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class AgendaLayoutTest {
    private val zone = TimeZone.of("Europe/Paris")
    private val day = LocalDate(2026, 11, 16)
    private val texts = AgendaTexts(allDay = "All day", noTitle = "(no title)", empty = "No events")

    private fun at(
        d: Int,
        h: Int,
        m: Int = 0,
    ) = EventTime.At(LocalDateTime(2026, 11, d, h, m).toInstant(zone).toEpochMilliseconds())

    private fun ev(
        summary: String,
        start: EventTime,
        end: EventTime,
        status: String = "",
        location: String = "",
    ) = CalendarEvent(
        CalEvent(uid = summary, summary = summary, status = status, location = location, start = start, end = end),
        "Cal",
        "#336699",
    )

    private fun allDay(summary: String) = ev(summary, EventTime.Day(day), EventTime.Day(LocalDate(2026, 11, 17)))

    private fun layout(
        events: List<CalendarEvent>,
        options: AgendaOptions = AgendaOptions(days = 2),
        display: AgendaDisplay = AgendaDisplay(template = "{{when}} {{summary}}"),
        now: LocalDateTime = LocalDateTime(2026, 11, 16, 12, 0),
    ) = AgendaLayout.layout(events, options, display, day, now.toInstant(zone), zone, DateLocales.en, texts)

    @Test
    fun sortsAllDayFirstThenByStartSplitsByDayAndDimsThePast() {
        val view =
            layout(
                listOf(
                    ev("late", at(16, 14), at(16, 15)),
                    ev("early", at(16, 9), at(16, 10)),
                    allDay("holiday"),
                    ev("tomorrow", at(17, 9), at(17, 9, 30)),
                    ev("cancelled", at(16, 11), at(16, 12), status = "CANCELLED"),
                ),
            )
        assertEquals(listOf("All day holiday", "09:00–10:00 early", "14:00–15:00 late"), view.days[0].items.map { it.markdown })
        assertEquals(listOf(false, true, false), view.days[0].items.map { it.dimmed })
        assertEquals(listOf("09:00–09:30 tomorrow"), view.days[1].items.map { it.markdown })
        val standup = view.days[0].items[1].occurrence
        assertEquals(AgendaOccurrence("early", "early", at(16, 9).instant, at(16, 10).instant, allDay = false), standup)
        assertEquals(
            true,
            view.days[0]
                .items[0]
                .occurrence.allDay,
        )
        assertEquals(listOf("Monday 16 November", "Tuesday 17 November"), view.days.map { it.header })
        assertNull(view.empty)
    }

    @Test
    fun showsOvernightEventsOnBothDaysWithOpenEnds() {
        val view = layout(listOf(ev("night", at(16, 22), at(17, 2))))
        assertEquals(
            "22:00–… night",
            view.days[0]
                .items
                .single()
                .markdown,
        )
        assertEquals(
            "…–02:00 night",
            view.days[1]
                .items
                .single()
                .markdown,
        )
    }

    @Test
    fun pastModesAndAllDayCanBeChanged() {
        val events = listOf(allDay("h"), ev("early", at(16, 9), at(16, 10)))
        assertEquals(listOf("09:00–10:00 early"), layout(events, AgendaOptions(allDay = false)).days[0].items.map { it.markdown })
        assertEquals(listOf("All day h"), layout(events, AgendaOptions(past = PastMode.HIDE)).days[0].items.map { it.markdown })
        assertEquals(listOf(false, false), layout(events, AgendaOptions(past = PastMode.SHOW)).days[0].items.map { it.dimmed })
    }

    @Test
    fun aSingleDayHasNoHeaderAndOneEmptyLine() {
        val view = layout(emptyList(), AgendaOptions())
        assertNull(view.days.single().header)
        assertNull(view.days.single().empty)
        assertEquals("No events", view.empty)

        val grouped = layout(emptyList(), AgendaOptions(days = 3, empty = "Nothing 🎉"))
        assertEquals(listOf("Nothing 🎉", "Nothing 🎉", "Nothing 🎉"), grouped.days.map { it.empty })
        assertNull(grouped.empty)

        // Past a week, empty days are left out entirely.
        val month = layout(listOf(ev("x", at(20, 9), at(20, 10))), AgendaOptions(days = 30))
        assertEquals(listOf("Friday 20 November"), month.days.mapNotNull { it.header })
    }

    @Test
    fun fillsEveryField() {
        val display =
            AgendaDisplay(
                template = "{{start}}|{{end}}|{{day}}|{{duration}}|{{calendar}}|{{status}}{{#location}} @ {{location}}{{/location}}",
                timeFormat = "h:mm A",
            )
        val view = layout(listOf(ev("", at(16, 14), at(16, 15, 30), status = "CONFIRMED", location = "Room")), display = display)
        assertEquals(
            "2:00 PM|3:30 PM|Monday 16 November|1h 30min|Cal|confirmed @ Room",
            view.days[0]
                .items
                .single()
                .markdown,
        )
        assertEquals(
            "(no title)",
            layout(
                listOf(ev("", at(16, 14), at(16, 15))),
                display = AgendaDisplay(template = "{{summary}}"),
            ).days[0].items.single().markdown,
        )
    }

    @Test
    fun aZeroLengthEventCountsOnlyWhereItStarts() {
        val view = layout(listOf(ev("reminder", at(17, 0), at(17, 0))))
        assertEquals(0, view.days[0].items.size)
        assertEquals(
            "00:00 reminder",
            view.days[1]
                .items
                .single()
                .markdown,
        )
    }

    @Test
    fun aFloatingEventIsReadInTheDeviceZone() {
        val view =
            layout(
                listOf(
                    ev("f", EventTime.Floating(LocalDateTime(2026, 11, 16, 9, 0)), EventTime.Floating(LocalDateTime(2026, 11, 16, 10, 0))),
                ),
            )
        assertEquals(
            "09:00–10:00 f",
            view.days[0]
                .items
                .single()
                .markdown,
        )
    }

    @Test
    fun formatsDurations() {
        assertEquals("1h 30min", AgendaLayout.formatDuration(90.minutes))
        assertEquals("1d 2h", AgendaLayout.formatDuration(26.hours))
        assertEquals("", AgendaLayout.formatDuration((-5).minutes))
    }
}
