package app.citti

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
}
