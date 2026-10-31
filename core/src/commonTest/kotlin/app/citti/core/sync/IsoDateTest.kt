package app.citti.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class IsoDateTest {
    @Test
    fun epochZeroIsTheFirstOfJanuary1970() = assertEquals("1970-01-01", isoDate(0))

    @Test
    fun leapDay() = assertEquals("2024-02-29", isoDate(1_709_164_800))

    @Test
    fun lastSecondOfADayInUtc() = assertEquals("2026-10-31", isoDate(1_793_491_199))

    @Test
    fun firstSecondOfTheNextDayInUtc() = assertEquals("2026-11-01", isoDate(1_793_491_200))

    @Test
    fun beforeTheEpoch() = assertEquals("1969-12-31", isoDate(-1))
}
