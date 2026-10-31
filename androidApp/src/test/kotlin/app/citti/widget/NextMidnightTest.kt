package app.citti.widget

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class NextMidnightTest {
    private val paris = TimeZone.of("Europe/Paris")

    private fun at(
        zone: TimeZone,
        y: Int,
        mo: Int,
        d: Int,
        h: Int = 0,
        mi: Int = 0,
    ) = LocalDateTime(y, mo, d, h, mi).toInstant(zone)

    @Test
    fun oneMinuteBeforeMidnightGivesTheNextMidnight() {
        assertEquals(at(paris, 2026, 11, 1), nextMidnight(at(paris, 2026, 10, 31, 23, 59), paris))
    }

    @Test
    fun exactlyMidnightGivesTheNextDay() {
        assertEquals(at(paris, 2026, 11, 1), nextMidnight(at(paris, 2026, 10, 31), paris))
    }

    @Test
    fun theDayBeforeDstEndsIs25HoursLong() {
        // Europe/Paris leaves summer time on 2026-10-25: that day lasts 25 hours.
        val start = at(paris, 2026, 10, 25)
        val next = nextMidnight(start + 30.minutes, paris)
        assertEquals(at(paris, 2026, 10, 26), next)
        assertEquals(25.hours, next - start)
    }

    @Test
    fun theDayDstStartsIs23HoursLong() {
        // Europe/Paris enters summer time on 2026-03-29: that day lasts 23 hours.
        val start = at(paris, 2026, 3, 29)
        val next = nextMidnight(start + 30.minutes, paris)
        assertEquals(at(paris, 2026, 3, 30), next)
        assertEquals(23.hours, next - start)
    }

    @Test
    fun aZoneChangeMovesTheMidnight() {
        val now = at(paris, 2026, 10, 31, 23, 0)
        val tokyo = TimeZone.of("Asia/Tokyo")
        assertEquals(at(paris, 2026, 11, 1), nextMidnight(now, paris))
        // 23:00 in Paris is already 06:00 on 1 November in Tokyo.
        assertEquals(at(tokyo, 2026, 11, 2), nextMidnight(now, tokyo))
    }
}
