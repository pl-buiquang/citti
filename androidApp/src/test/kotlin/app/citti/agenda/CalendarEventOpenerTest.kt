package app.citti.agenda

import app.citti.core.agenda.AgendaOccurrence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class CalendarEventOpenerTest {
    private val nine = Instant.parse("2026-10-30T08:00:00Z")
    private val occurrence = AgendaOccurrence("standup@x", "Standup", nine, Instant.parse("2026-10-30T08:30:00Z"), allDay = false)

    private fun instance(
        id: Long,
        begin: Instant,
        uid: String? = "standup@x",
        title: String = "Standup",
    ) = ProviderInstance(id, begin.toEpochMilliseconds(), begin.toEpochMilliseconds() + 1_800_000, uid, title, allDay = false)

    @Test
    fun theSameUidAtTheSameTimeWins() {
        val dayBefore = instance(1, Instant.parse("2026-10-29T08:00:00Z"))
        val today = instance(2, nine)
        val other = instance(3, nine, uid = "other@x")
        assertEquals(today, CalendarEventOpener.pick(listOf(dayBefore, other, today), occurrence))
    }

    @Test
    fun theSameUidNearestInTimeComesNext() {
        val moved = instance(4, Instant.parse("2026-10-30T09:00:00Z"))
        val far = instance(5, Instant.parse("2026-10-31T08:00:00Z"))
        assertEquals(moved, CalendarEventOpener.pick(listOf(far, moved), occurrence))
    }

    @Test
    fun withoutAUidTheTitleAndTimeMustMatch() {
        val noUid = instance(6, nine, uid = null)
        assertEquals(noUid, CalendarEventOpener.pick(listOf(noUid), occurrence))
        assertNull(CalendarEventOpener.pick(listOf(instance(7, nine, uid = null, title = "Other")), occurrence))
        assertNull(CalendarEventOpener.pick(listOf(instance(8, nine, uid = "other@x")), occurrence))
        assertNull(CalendarEventOpener.pick(emptyList(), occurrence))
    }

    @Test
    fun anAllDayInstanceIsMatchedByItsDay() {
        // Local midnight in Paris vs the provider's UTC midnight.
        val day =
            AgendaOccurrence("h@x", "Holiday", Instant.parse("2026-10-30T22:00:00Z"), Instant.parse("2026-10-31T23:00:00Z"), allDay = true)
        val stored = ProviderInstance(9, Instant.parse("2026-10-31T00:00:00Z").toEpochMilliseconds(), 0, null, "Holiday", allDay = true)
        assertEquals(stored, CalendarEventOpener.pick(listOf(stored), day))
    }
}
