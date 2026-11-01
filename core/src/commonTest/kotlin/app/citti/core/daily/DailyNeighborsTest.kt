package app.citti.core.daily

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DailyNeighborsTest {
    private val notes =
        mapOf(
            LocalDate(2026, 8, 20) to DailyEntry(1, null),
            LocalDate(2026, 10, 29) to DailyEntry(2, null),
            LocalDate(2026, 10, 30) to DailyEntry(3, null),
        )

    @Test
    fun emptyDaysAreSkipped() {
        val n = notes.neighborsOf(LocalDate(2026, 10, 29))
        assertEquals(DailyNeighbor(LocalDate(2026, 8, 20), 1), n.previous)
        assertEquals(DailyNeighbor(LocalDate(2026, 10, 30), 3), n.next)
    }

    @Test
    fun theEndsHaveOneSideOnly() {
        assertNull(notes.neighborsOf(LocalDate(2026, 8, 20)).previous)
        assertNull(notes.neighborsOf(LocalDate(2026, 10, 30)).next)
    }

    @Test
    fun aDateWithoutANoteStillHasNeighbors() {
        val n = notes.neighborsOf(LocalDate(2026, 9, 15))
        assertEquals(1L, n.previous?.localId)
        assertEquals(2L, n.next?.localId)
    }
}
