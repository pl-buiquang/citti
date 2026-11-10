package app.citti.ui.note

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NeighborSwipeTest {
    @Test
    fun aLongQuickHorizontalMoveSwipes() {
        assertEquals(SwipeDirection.Next, swipeDirection(dx = -400f, dy = 30f, ms = 200, width = 1000))
        assertEquals(SwipeDirection.Previous, swipeDirection(dx = 400f, dy = -30f, ms = 200, width = 1000))
    }

    @Test
    fun aShortMoveIsNotASwipe() {
        assertNull(swipeDirection(dx = -100f, dy = 0f, ms = 100, width = 1000))
    }

    @Test
    fun aDiagonalScrollIsNotASwipe() {
        assertNull(swipeDirection(dx = -400f, dy = 300f, ms = 200, width = 1000))
    }

    @Test
    fun aSlowDragIsNotASwipe() {
        assertNull(swipeDirection(dx = -600f, dy = 0f, ms = 900, width = 1000))
    }
}
