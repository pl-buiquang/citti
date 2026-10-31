package app.citti.ui.appearance

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Hue 0..360, saturation and value 0..1, alpha 0..1. */
data class Hsv(
    val h: Float,
    val s: Float,
    val v: Float,
    val a: Float = 1f,
)

/** ARGB (`0xAARRGGBB`) to [Hsv]. */
fun argbToHsv(argb: Long): Hsv {
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val a = (argb shr 24 and 0xFF) / 255f
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val d = mx - mn
    val h =
        when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
    return Hsv(if (h < 0f) h + 360f else h, if (mx == 0f) 0f else d / mx, mx, a)
}

/** [Hsv] to ARGB (`0xAARRGGBB`). */
fun hsvToArgb(hsv: Hsv): Long {
    val c = hsv.v * hsv.s
    val x = c * (1f - abs((hsv.h / 60f) % 2f - 1f))
    val m = hsv.v - c
    val (r, g, b) =
        when ((hsv.h / 60f).toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

    fun ch(f: Float) = ((f + m) * 255f).roundToInt().coerceIn(0, 255).toLong()
    val a = (hsv.a * 255f).roundToInt().coerceIn(0, 255).toLong()
    return (a shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
}
