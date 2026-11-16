package app.citti.core.agenda

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgendaOptionsTest {
    @Test
    fun parsesKnownKeysAndReportsErrors() {
        val parsed =
            parseAgendaOptions(
                "date: 2026-11-16\ndays: 3\ncalendars: Personal, Work\ntemplate: {{when}} {{summary}}\npast: hide\n" +
                    "# comment\n// comment\n\nfoo: bar\ndays: x\nno colon\npast: maybe\ngroup: yes\nallday: off\nempty: Nothing",
            )
        assertEquals(
            AgendaOptions(
                date = "2026-11-16",
                days = 3,
                calendars = listOf("Personal", "Work"),
                template = "{{when}} {{summary}}",
                past = PastMode.HIDE,
                empty = "Nothing",
                group = true,
                allDay = false,
            ),
            parsed.options,
        )
        assertEquals(
            listOf(
                OptionError.UnknownKey("foo"),
                OptionError.BadDays("x"),
                OptionError.NoColon("no colon"),
                OptionError.BadPast("maybe"),
            ),
            parsed.errors,
        )
    }

    @Test
    fun keepsColonsInsideValues() {
        assertEquals("{{start}}: {{summary}}", parseAgendaOptions("template: {{start}}: {{summary}}").options.template)
    }

    @Test
    fun daysMustBeBetweenOneAndAYear() {
        assertEquals(366, parseAgendaOptions("days: 366").options.days)
        assertEquals(listOf(OptionError.BadDays("0")), parseAgendaOptions("days: 0").errors)
        assertEquals(listOf(OptionError.BadDays("367")), parseAgendaOptions("days: 367").errors)
    }

    @Test
    fun anEmptyBlockHasNoOptions() {
        assertEquals(ParsedOptions(AgendaOptions(), emptyList()), parseAgendaOptions(""))
    }

    private val today = LocalDate(2026, 11, 16)

    @Test
    fun defaultsToTheDailyNoteDateElseToday() {
        assertEquals(LocalDate(2026, 11, 10), resolveStartDate(null, "2026-11-10", today))
        assertEquals(LocalDate(2026, 11, 10), resolveStartDate("", "2026-11-10 Monday", today))
        assertEquals(today, resolveStartDate(null, "Some note", today))
        assertEquals(today, resolveStartDate(null, "2026-02-31", today))
    }

    @Test
    fun supportsKeywordsAndOffsets() {
        assertEquals(today, resolveStartDate("Today", "2026-01-01", today))
        assertEquals(LocalDate(2026, 11, 17), resolveStartDate("tomorrow", "", today))
        assertEquals(LocalDate(2026, 11, 15), resolveStartDate("yesterday", "", today))
        assertEquals(LocalDate(2026, 11, 14), resolveStartDate("-2", "", today))
        assertEquals(LocalDate(2026, 11, 18), resolveStartDate("+2", "", today))
        assertEquals(LocalDate(2026, 12, 1), resolveStartDate("2026-12-01", "", today))
        assertNull(resolveStartDate("garbage", "", today))
        assertNull(resolveStartDate("2026-13-01", "", today))
    }
}
