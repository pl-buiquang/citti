package app.citti.ui.theme

import androidx.compose.ui.text.font.FontFamily
import app.citti.core.appearance.Appearance
import app.citti.core.appearance.BaseTheme
import app.citti.core.appearance.ColorToken
import app.citti.core.appearance.Corners
import app.citti.core.appearance.CustomTheme
import app.citti.core.appearance.FontFace
import app.citti.core.appearance.FontRole
import app.citti.core.appearance.RoleOverrides
import app.citti.core.appearance.ThemeChoice
import org.junit.Assume.assumeTrue
import kotlin.test.Test

/** `resolveTheme` timing (M8 spec §11): p50/p95 over 1,000 resolutions. Skipped unless `CITTI_BENCH=1`. */
class ResolveThemeBenchmark {
    @Test
    fun resolveThemeP50P95() {
        assumeTrue(System.getenv("CITTI_BENCH") == "1")
        val fonts = FontResolver { FontFamily.Default }
        val appearance =
            Appearance(
                ThemeChoice.CUSTOM,
                custom =
                    CustomTheme(
                        BaseTheme.PAPER,
                        light = ColorToken.entries.associateWith { 0xFF336699L },
                        roles =
                            RoleOverrides(
                                fonts = FontRole.entries.associateWith { FontFace.SYSTEM },
                                textScale = 1.1,
                                corners = Corners.SOFT,
                            ),
                    ),
            )
        repeat(200) { resolveTheme(appearance, false, null, fonts) }
        val times = LongArray(1000)
        for (i in times.indices) {
            val t0 = System.nanoTime()
            resolveTheme(appearance, false, null, fonts)
            times[i] = System.nanoTime() - t0
        }
        times.sort()
        println("resolveTheme p50=${times[500] / 1000} us p95=${times[950] / 1000} us")
    }
}
