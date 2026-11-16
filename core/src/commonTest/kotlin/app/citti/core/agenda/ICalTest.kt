package app.citti.core.agenda

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ICalTest {
    private fun ics(body: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n$body\r\nEND:VCALENDAR\r\n"

    private fun at(iso: String) = EventTime.At(Instant.parse(iso).toEpochMilliseconds())

    @Test
    fun parsesAUtcEventWithEscapedTextAndFoldedLines() {
        val e =
            ICal
                .events(
                    ics(
                        listOf(
                            "BEGIN:VEVENT",
                            "UID:1",
                            "DTSTART:20261116T090000Z",
                            "DTEND:20261116T100000Z",
                            "SUMMARY:Stand\\, up\\; daily",
                            "DESCRIPTION:line1\\nline2 that is",
                            "  folded",
                            "LOCATION:Room 1",
                            "URL:https://example.com/a",
                            "BEGIN:VALARM",
                            "SUMMARY:alarm summary must be ignored",
                            "END:VALARM",
                            "END:VEVENT",
                        ).joinToString("\r\n"),
                    ),
                ).single()
        assertEquals("Stand, up; daily", e.summary)
        assertEquals("line1\nline2 that is folded", e.description)
        assertEquals("Room 1", e.location)
        assertEquals("https://example.com/a", e.url)
        assertEquals(at("2026-11-16T09:00:00Z"), e.start)
        assertEquals(at("2026-11-16T10:00:00Z"), e.end)
        assertEquals(false, e.allDay)
    }

    @Test
    fun parsesAllDayEventsWithAnImplicitOneDayEnd() {
        val e = ICal.events(ics("BEGIN:VEVENT\nUID:2\nDTSTART;VALUE=DATE:20261116\nSUMMARY:Holiday\nEND:VEVENT")).single()
        assertEquals(true, e.allDay)
        assertEquals(EventTime.Day(LocalDate(2026, 11, 16)), e.start)
        assertEquals(EventTime.Day(LocalDate(2026, 11, 17)), e.end)
    }

    @Test
    fun convertsTzidTimesAndDuration() {
        val e =
            ICal
                .events(ics("BEGIN:VEVENT\nUID:3\nDTSTART;TZID=\"America/New_York\":20260701T090000\nDURATION:PT1H30M\nEND:VEVENT"))
                .single()
        assertEquals(at("2026-07-01T13:00:00Z"), e.start)
        assertEquals(at("2026-07-01T14:30:00Z"), e.end)
    }

    @Test
    fun handlesDstAndVendorPrefixedZones() {
        val winter = ICal.events(ics("BEGIN:VEVENT\nUID:4\nDTSTART;TZID=Europe/Paris:20260115T120000\nEND:VEVENT")).single()
        assertEquals(at("2026-01-15T11:00:00Z"), winter.start)
        val summer =
            ICal
                .events(ics("BEGIN:VEVENT\nUID:5\nDTSTART;TZID=/mozilla.org/20050126_1/Europe/Paris:20260715T120000\nEND:VEVENT"))
                .single()
        assertEquals(at("2026-07-15T10:00:00Z"), summer.start)
        assertEquals(summer.start, summer.end)
    }

    @Test
    fun anUnknownZoneOrNoZoneIsFloating() {
        val e =
            ICal
                .events(ics("BEGIN:VEVENT\nUID:6\nDTSTART;TZID=W. Europe Standard Time:20261116T090000\nDURATION:PT30M\nEND:VEVENT"))
                .single()
        assertEquals(EventTime.Floating(LocalDateTime(2026, 11, 16, 9, 0)), e.start)
        assertEquals(EventTime.Floating(LocalDateTime(2026, 11, 16, 9, 30)), e.end)
        val f = ICal.events(ics("BEGIN:VEVENT\nUID:7\nDTSTART:20261116T090000\nEND:VEVENT")).single()
        assertEquals(EventTime.Floating(LocalDateTime(2026, 11, 16, 9, 0)), f.start)
    }

    @Test
    fun skipsEventsWithoutDtstartAndKeepsStatus() {
        val events =
            ICal.events(
                ics(
                    "BEGIN:VEVENT\nUID:a\nSUMMARY:no start\nEND:VEVENT\nBEGIN:VEVENT\nUID:b\nDTSTART:20261116T090000Z\nSTATUS:cancelled\nEND:VEVENT",
                ),
            )
        assertEquals(listOf("b"), events.map { it.uid })
        assertEquals("CANCELLED", events.single().status)
    }

    @Test
    fun helpers() {
        assertEquals(listOf("A:12", "B:3"), ICal.unfold("A:1\r\n 2\r\nB:3"))
        assertEquals(1.days, ICal.parseDuration("P1D"))
        assertEquals((-15).minutes, ICal.parseDuration("-PT15M"))
        assertEquals(7.days, ICal.parseDuration("P1W"))
        assertNull(ICal.zone("Not/AZone"))
        assertEquals("Europe/Paris", ICal.zone("Europe/Paris")?.id)
    }

    @Test
    fun eventsSurviveJson() {
        val events =
            ICal.events(
                ics(
                    "BEGIN:VEVENT\nUID:a\nDTSTART;VALUE=DATE:20261116\nEND:VEVENT\nBEGIN:VEVENT\nUID:b\nDTSTART:20261116T090000Z\n" +
                        "DTEND:20261116T090000\nEND:VEVENT",
                ),
            )
        assertEquals(events, Json.decodeFromString<List<CalEvent>>(Json.encodeToString(events)))
    }
}
