package app.citti.ui.browse

import kotlin.test.Test
import kotlin.test.assertEquals

/** A calendar in UTC+1 with 365-day years starting at 1970, which is all [rowDate] needs from the platform. */
private object FakeCalendar : RowDateFormatter {
    const val OFFSET = 3600L
    const val DAY = 86_400L

    override fun localDay(epochSec: Long): Long = (epochSec + OFFSET).floorDiv(DAY)

    override fun year(epochSec: Long): Int = 1970 + (localDay(epochSec) / 365).toInt()

    override fun absolute(
        epochSec: Long,
        withYear: Boolean,
    ): String = if (withYear) "day ${localDay(epochSec)} of ${year(epochSec)}" else "day ${localDay(epochSec)}"
}

class RowDateTest {
    private val day = FakeCalendar.DAY

    /** Local noon of day 400 (year 1971): far enough from both midnights and from a year boundary. */
    private val noon = 400 * day + day / 2 - FakeCalendar.OFFSET

    private fun at(
        now: Long,
        modified: Long,
    ) = rowDate(modified, now, FakeCalendar)

    @Test
    fun underAMinuteIsNow() {
        assertEquals(RowDate.Now, at(noon, noon))
        assertEquals(RowDate.Now, at(noon, noon - 59))
        assertEquals(RowDate.Now, at(noon, noon + 300))
    }

    @Test
    fun minutesThenHoursTheSameDay() {
        assertEquals(RowDate.Minutes(2), at(noon, noon - 150))
        assertEquals(RowDate.Minutes(59), at(noon, noon - 3599))
        assertEquals(RowDate.Hours(1), at(noon, noon - 3600))
        assertEquals(RowDate.Hours(11), at(noon, noon - 11 * 3600 - 59 * 60))
    }

    @Test
    fun yesterdayIsThePreviousCalendarDay() {
        val justAfterMidnight = noon - day / 2 + 600
        // 2 h ago, but before local midnight.
        assertEquals(RowDate.Yesterday, at(justAfterMidnight, justAfterMidnight - 2 * 3600))
        // 20 min ago across midnight is still minutes.
        assertEquals(RowDate.Minutes(20), at(justAfterMidnight, justAfterMidnight - 1200))
        // 30 h ago, at 6:00 the previous day.
        assertEquals(RowDate.Yesterday, at(noon, noon - 30 * 3600))
    }

    @Test
    fun daysUnderAWeekThenAbsolute() {
        assertEquals(RowDate.Days(3), at(noon, noon - 3 * day))
        assertEquals(RowDate.Days(6), at(noon, noon - 6 * day))
        assertEquals(RowDate.Absolute("day 392"), at(noon, noon - 8 * day))
    }

    @Test
    fun lastYearShowsTheYear() {
        assertEquals(RowDate.Absolute("day 300 of 1970"), at(noon, noon - 100 * day))
    }
}
