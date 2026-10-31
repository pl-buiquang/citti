package app.citti.ui.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZoomStateTest {
    // A 1000x2000 view with an Image fitted into it at 1000x1500.
    private val vw = 1000f
    private val vh = 2000f
    private val cw = 1000f
    private val ch = 1500f

    private fun ZoomState.pinch(
        zoom: Float,
        x: Float = vw / 2,
        y: Float = vh / 2,
    ) = transform(x, y, 0f, 0f, zoom, vw, vh, cw, ch)

    @Test
    fun scaleStaysBetween1xAnd5x() {
        assertEquals(5f, ZoomState().pinch(100f).scale)
        assertEquals(1f, ZoomState().pinch(0.1f).scale)
        assertEquals(ZoomState(), ZoomState().pinch(0.1f))
    }

    @Test
    fun anUnzoomedImageCannotBePanned() {
        assertEquals(ZoomState(), ZoomState().transform(500f, 1000f, 300f, 300f, 1f, vw, vh, cw, ch))
    }

    @Test
    fun panStopsAtTheEdgesOfTheZoomedImage() {
        val twice = ZoomState().pinch(2f)
        // Wider than the view by (2000 - 1000) / 2 on each side; taller image (3000) fits in the 2000 view: 500 each way.
        val panned = twice.transform(500f, 1000f, 9999f, -9999f, 1f, vw, vh, cw, ch)
        assertEquals(500f, panned.offsetX)
        assertEquals(-500f, panned.offsetY)
    }

    @Test
    fun anImageSmallerThanTheViewStaysCenteredOnAnAxis() {
        // Fitted height 1500 at 1.2x = 1800 < 2000: no vertical pan at all.
        val z = ZoomState().pinch(1.2f).transform(500f, 1000f, 0f, 400f, 1f, vw, vh, cw, ch)
        assertEquals(0f, z.offsetY)
    }

    @Test
    fun zoomingKeepsThePointUnderTheFingersInPlace() {
        // Pinch at x = 750, which is 250 right of the centre: the content shifts left by 250 when the scale doubles.
        val z = ZoomState().pinch(2f, x = 750f, y = 1000f)
        assertEquals(-250f, z.offsetX)
    }

    @Test
    fun doubleTapTogglesBetween1xAnd2x() {
        val once = ZoomState().toggle(750f, 1000f, vw, vh, cw, ch)
        assertEquals(2f, once.scale)
        assertTrue(once.zoomed)
        val twice = once.toggle(100f, 100f, vw, vh, cw, ch)
        assertEquals(ZoomState(), twice)
        assertFalse(twice.zoomed)
    }

    @Test
    fun fittedKeepsTheAspectRatioInsideTheView() {
        assertEquals(1000f to 1500f, ZoomState.fitted(1000f, 2000f, 2000f, 3000f))
        assertEquals(1000f to 500f, ZoomState.fitted(1000f, 2000f, 4000f, 2000f))
        // Unknown size: the whole view.
        assertEquals(1000f to 2000f, ZoomState.fitted(1000f, 2000f, 0f, 0f))
    }
}
