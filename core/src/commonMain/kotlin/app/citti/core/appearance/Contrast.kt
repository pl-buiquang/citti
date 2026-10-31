package app.citti.core.appearance

import kotlin.math.pow

/** The WCAG minimum contrast ratio of body text. */
const val MIN_BODY_CONTRAST = 4.5

/** WCAG contrast ratio (1..21) between two opaque ARGB colors; alpha is ignored (composite translucent colors over `bg` first). */
fun contrastRatio(
    a: Long,
    b: Long,
): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

private fun luminance(argb: Long): Double {
    fun channel(shift: Int): Double {
        val c = (argb shr shift and 0xFF) / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}
