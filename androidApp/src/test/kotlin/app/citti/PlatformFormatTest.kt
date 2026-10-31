package app.citti

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformFormatTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val dates = JavaTimeRowDateFormatter(Locale.UK) { paris }

    private fun epoch(text: String) = LocalDateTime.parse(text).atZone(paris).toEpochSecond()

    @Test
    fun calendarDaysFollowTheDeviceZone() {
        // 00:30 in Paris is still the previous day in UTC.
        assertEquals(dates.localDay(epoch("2026-10-30T12:00")) + 1, dates.localDay(epoch("2026-10-31T00:30")))
        assertEquals(2026, dates.year(epoch("2026-01-01T00:30")))
    }

    @Test
    fun absoluteDates() {
        assertEquals("12 Oct", dates.absolute(epoch("2026-10-12T09:00"), withYear = false))
        assertEquals("12 Oct 2025", dates.absolute(epoch("2025-10-12T09:00"), withYear = true))
    }

    @Test
    fun collatorIgnoresCaseAndAccents() {
        val collator = localeTitleCollator(Locale.FRENCH)
        assertEquals(0, collator.compare("ete", "Été"))
        assertTrue(collator.compare("abricot", "Été") < 0)
        assertTrue(collator.compare("Zèbre", "été") > 0)
    }

    @Test
    fun calendarLabelsInEnglish() {
        val labels = JavaTimeCalendarLabels(Locale.UK)
        assertEquals("October 2026", labels.monthYear(YearMonth(2026, 10)))
        assertEquals("October", labels.monthName(YearMonth(2026, 10)))
        assertEquals("M", labels.weekdayInitial(DayOfWeek.MONDAY))
        assertEquals("Saturday", labels.weekdayName(DayOfWeek.SATURDAY))
        assertEquals("Sat 31 Oct", labels.shortDate(LocalDate(2026, 10, 31)))
        assertEquals("12 Oct", labels.dayMonth(LocalDate(2026, 10, 12)))
        assertEquals("31 October", labels.dayMonthLong(LocalDate(2026, 10, 31)))
    }

    @Test
    fun calendarLabelsFollowTheLocale() {
        val french = JavaTimeCalendarLabels(Locale.FRANCE)
        assertEquals("Octobre 2026", french.monthYear(YearMonth(2026, 10)))
        assertEquals("octobre", french.monthName(YearMonth(2026, 10)))
        assertEquals(DayOfWeek.MONDAY, french.defaultWeekStart())
        assertEquals(DayOfWeek.SUNDAY, JavaTimeCalendarLabels(Locale.US).defaultWeekStart())
    }
}
