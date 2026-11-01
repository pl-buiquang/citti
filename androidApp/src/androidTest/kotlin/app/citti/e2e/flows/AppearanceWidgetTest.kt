package app.citti.e2e.flows

import androidx.compose.ui.graphics.toArgb
import androidx.glance.appwidget.updateAll
import app.citti.core.appearance.ModeChoice
import app.citti.core.appearance.ThemeChoice
import app.citti.e2e.AppIntent
import app.citti.e2e.E2e
import app.citti.e2e.WidgetFlow
import app.citti.e2e.robots.WidgetRobot
import app.citti.e2e.robots.WidgetStrings
import app.citti.e2e.rules.E2eRules
import app.citti.e2e.rules.Setup
import app.citti.ui.theme.ThemeFamily
import app.citti.ui.theme.notesColors
import app.citti.widget.FavoritesWidget
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertTrue

/** A change of the Appearance redraws the placed Favorites widget (M8). Mode is fixed so the launcher's night mode does not matter. */
@WidgetFlow
class AppearanceWidgetTest {
    @get:Rule val e2e = E2eRules(Setup.signedInAndSynced())

    private val intent = AppIntent(e2e)

    @After
    fun finishApp() {
        // The Appearance lives in the DB the next flow may reuse: leave the defaults.
        E2e.container.appearanceSettings.apply {
            setTheme(ThemeChoice.SIGNAL)
            setMode(ModeChoice.SYSTEM)
        }
        intent.finish()
    }

    private val widget = WidgetRobot(WidgetStrings.FAVORITES_RECEIVER) { FavoritesWidget().updateAll(it) }

    /** Polls the screenshot until the widget background is [expected] (the redraw is debounced 500 ms). */
    private fun assertBackground(expected: Int) {
        var last: Int? = null
        repeat(ATTEMPTS) {
            last = widget.backgroundNextTo(HEADER)
            if (last?.let { close(it, expected) } == true) return
            widget.settle(SETTLE_MS)
        }
        assertTrue(false, "widget background ${last?.let { "#%08X".format(it) }}, expected #%08X".format(expected))
    }

    private fun close(
        a: Int,
        b: Int,
    ): Boolean = listOf(16, 8, 0).all { abs(((a shr it) and 0xFF) - ((b shr it) and 0xFF)) <= TOLERANCE }

    @Test
    fun switchingThemeAndModeRedrawsTheWidget() {
        val settings = E2e.container.appearanceSettings
        settings.setTheme(ThemeChoice.SIGNAL)
        settings.setMode(ModeChoice.LIGHT)
        widget.place()
        widget.waitForText(HEADER)
        assertBackground(notesColors(ThemeFamily.Signal, dark = false).bg.toArgb())

        settings.setMode(ModeChoice.DARK)
        settings.setTheme(ThemeChoice.INDEX)
        assertBackground(notesColors(ThemeFamily.Index, dark = true).bg.toArgb())
    }

    private companion object {
        const val HEADER = "Favorites"
        const val ATTEMPTS = 20
        const val SETTLE_MS = 1_000L
        const val TOLERANCE = 3
    }
}
