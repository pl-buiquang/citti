package app.citti.ui.image

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pinch-zoom and pan of the Image viewer, as pure math: scale 1x to 5x, a double tap toggles 2x, and the Image
 * can never be panned past its edges. Sizes are in pixels: [view] is the area on screen, [content] the Image at
 * scale 1 (fitted into the view).
 */
data class ZoomState(
    val scale: Float = MIN_SCALE,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    /** A gesture frame: [zoom] multiplies the scale around [centroid] (view coordinates), then [panX]/[panY] move it. */
    fun transform(
        centroidX: Float,
        centroidY: Float,
        panX: Float,
        panY: Float,
        zoom: Float,
        viewW: Float,
        viewH: Float,
        contentW: Float,
        contentH: Float,
    ): ZoomState {
        val next = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val factor = next / scale
        // Keep the point under the fingers where it is: offsets are relative to the view centre.
        val cx = centroidX - viewW / 2f
        val cy = centroidY - viewH / 2f
        return ZoomState(next, (offsetX - cx) * factor + cx + panX, (offsetY - cy) * factor + cy + panY)
            .clamped(viewW, viewH, contentW, contentH)
    }

    /** Double tap: back to 1x when zoomed in, else 2x around the tapped point. */
    fun toggle(
        tapX: Float,
        tapY: Float,
        viewW: Float,
        viewH: Float,
        contentW: Float,
        contentH: Float,
    ): ZoomState =
        if (scale > MIN_SCALE + EPS) {
            ZoomState()
        } else {
            transform(tapX, tapY, 0f, 0f, DOUBLE_TAP_SCALE / MIN_SCALE, viewW, viewH, contentW, contentH)
        }

    fun clamped(
        viewW: Float,
        viewH: Float,
        contentW: Float,
        contentH: Float,
    ): ZoomState {
        if (scale <= MIN_SCALE + EPS) return ZoomState()
        val maxX = max(0f, (contentW * scale - viewW) / 2f)
        val maxY = max(0f, (contentH * scale - viewH) / 2f)
        return copy(offsetX = offsetX.coerceIn(-maxX, maxX), offsetY = offsetY.coerceIn(-maxY, maxY))
    }

    val zoomed: Boolean get() = abs(scale - MIN_SCALE) > EPS

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 5f
        const val DOUBLE_TAP_SCALE = 2f
        private const val EPS = 0.001f

        /** The Image at scale 1, fitted (not cropped) into the view. */
        fun fitted(
            viewW: Float,
            viewH: Float,
            imageW: Float,
            imageH: Float,
        ): Pair<Float, Float> {
            if (imageW <= 0f || imageH <= 0f) return viewW to viewH
            val s = min(viewW / imageW, viewH / imageH)
            return imageW * s to imageH * s
        }
    }
}
