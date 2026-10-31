package app.citti.e2e.robots

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Places and drives one widget on the launcher of the `api36Google` device (UiAutomator; the ATD image has no
 * launcher that can pin widgets). [receiver] is the fully qualified class name of the widget's receiver, for example
 * `app.citti.widget.FavoritesWidgetReceiver`. Only the `@WidgetFlow` classes use it.
 *
 * The pin dialog and the home screen are the Pixel launcher's: see `~/scratch/citti-m7a-spike/NOTES.md`.
 */
class WidgetRobot(
    private val receiver: String,
    /** Redraws the placed widget (`SomeWidget().updateAll(context)`): the first draw does not always come by itself. */
    private val redraw: suspend (Context) -> Unit = {},
) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val manager = context.getSystemService(AppWidgetManager::class.java)
    private val provider = ComponentName(context, receiver)

    /** The id of a placed instance of this widget, or null. */
    fun widgetId(): Int? = manager.getAppWidgetIds(provider).firstOrNull()

    fun goHome() {
        device.pressHome()
        device.wait(Until.hasObject(By.pkg(launcherPackage())), TIMEOUT.inWholeMilliseconds)
    }

    /**
     * Removes every widget from the launcher by clearing its data: a test cannot remove a single widget (see
     * [remove]), and widgets placed by earlier flows would push this one to a second page. The launcher restarts empty.
     */
    fun reset() {
        device.executeShellCommand("pm clear ${launcherPackage()}")
        waitUntil("no widget of $receiver left") { widgetId() == null }
        goHome()
    }

    /** Pins the widget to the home screen of a freshly reset launcher and returns its id. */
    fun place(): Int {
        reset()
        check(manager.isRequestPinAppWidgetSupported) { "This launcher cannot pin widgets: run on api36Google" }
        check(manager.requestPinAppWidget(provider, null, null)) { "requestPinAppWidget refused for $receiver" }
        val launcher = launcherPackage()
        check(device.wait(Until.hasObject(By.res(launcher, "add_item_bottom_sheet_content")), TIMEOUT.inWholeMilliseconds)) {
            "The pin dialog of $launcher did not show"
        }
        val add = device.wait(Until.findObject(By.text(ADD_TO_HOME)), TIMEOUT.inWholeMilliseconds)
        checkNotNull(add) { "No '$ADD_TO_HOME' button in the pin dialog" }.click()
        waitUntil("a placed $receiver") { widgetId() != null }
        runBlocking { redraw(context) }
        return checkNotNull(widgetId())
    }

    /**
     * Best effort: drags the widget to the top bar of the launcher. It did not remove the widget on the Pixel
     * launcher of the google API 36 image, so it returns whether the widget is really gone; do not rely on it.
     * [widgetText] is any text the widget shows.
     */
    fun remove(widgetText: String): Boolean {
        goHome()
        for ((x, y) in listOf(270 to 200, 810 to 200)) {
            val widget = device.findObject(By.text(widgetText)) ?: break
            widget.drag(android.graphics.Point(x, y), DRAG_SPEED)
            device.waitForIdle()
            if (widgetId() == null) return true
        }
        return widgetId() == null
    }

    /** Whether [text] shows on the home screen (inside the widget or not). */
    fun hasText(text: String): Boolean = device.hasObject(By.text(text))

    fun waitForText(
        text: String,
        timeout: Duration = TIMEOUT,
    ): UiObject2 =
        checkNotNull(device.wait(Until.findObject(By.text(text)), timeout.inWholeMilliseconds)) {
            "'$text' did not show on the launcher; on screen:\n${texts().joinToString("\n")}"
        }

    fun waitForTextGone(
        text: String,
        timeout: Duration = TIMEOUT,
    ) = check(device.wait(Until.gone(By.text(text)), timeout.inWholeMilliseconds)) { "'$text' is still on the launcher" }

    /** Taps the widget row or text [text], then waits for the app to be in front. */
    fun tapText(
        text: String,
        timeout: Duration = TIMEOUT,
    ) {
        waitForText(text, timeout).click()
        check(device.wait(Until.hasObject(By.pkg(context.packageName)), timeout.inWholeMilliseconds)) {
            "The app did not open after tapping '$text'"
        }
    }

    /**
     * The Note picker the launcher opens after "Add to home screen" (or when a widget whose Note is gone is tapped):
     * picks the Note titled [title] from its list, which closes the picker.
     */
    fun pickNote(title: String) {
        checkNotNull(device.wait(Until.findObject(By.text(WidgetStrings.PICKER_TITLE)), TIMEOUT.inWholeMilliseconds)) {
            "The Note picker did not show"
        }
        checkNotNull(device.wait(Until.findObject(By.text(title)), TIMEOUT.inWholeMilliseconds)) {
            "'$title' is not in the Note picker; on screen:\n" +
                device.findObjects(By.pkg(context.packageName)).flatMap { listOfNotNull(it.text, it.contentDescription) }.joinToString("\n")
        }.click()
        check(device.wait(Until.gone(By.text(WidgetStrings.PICKER_TITLE)), TIMEOUT.inWholeMilliseconds)) { "The Note picker stayed open" }
    }

    /** Every text and content description on the launcher, in tree order: what a failure message shows. */
    fun texts(): List<String> =
        device
            .findObjects(By.pkg(launcherPackage()))
            .flatMap { listOfNotNull(it.text, it.contentDescription) }
            .filter { it.isNotEmpty() }

    private fun waitUntil(
        what: String,
        timeout: Duration = TIMEOUT,
        condition: () -> Boolean,
    ) {
        // Poll on UiAutomator's idle wait instead of sleeping (the e2eNoSleep rule).
        val end = System.nanoTime() + timeout.inWholeNanoseconds
        while (!condition()) {
            check(System.nanoTime() < end) { "Timed out waiting for $what" }
            device.waitForIdle(POLL_MS)
        }
    }

    /**
     * The launcher app. `resolveActivity` can answer `com.android.settings` (its `FallbackHome`, a HOME activity that
     * stands in while the launcher starts), so every HOME activity is listed and Settings is skipped.
     */
    private fun launcherPackage(): String =
        context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }
            .firstOrNull { it != SETTINGS }
            ?: FALLBACK_LAUNCHER

    private companion object {
        const val ADD_TO_HOME = "Add to home screen"
        const val SETTINGS = "com.android.settings"
        const val FALLBACK_LAUNCHER = "com.google.android.apps.nexuslauncher"
        const val DRAG_SPEED = 600
        const val POLL_MS = 250L
        val TIMEOUT = 10.seconds
    }
}

/** The English copy of the widgets (`androidApp/src/main/res/values/strings.xml`). */
object WidgetStrings {
    const val FAVORITES_RECEIVER = "app.citti.widget.FavoritesWidgetReceiver"
    const val NOTE_RECEIVER = "app.citti.widget.NoteWidgetReceiver"
    const val DAILY_RECEIVER = "app.citti.widget.DailyNoteWidgetReceiver"
    const val TODAY_ROW = "Today"
    const val NOTE_MORE = "Open to read more"
    const val NOTE_GONE = "Note not available. Tap to pick another."
    const val DAILY_MISSING = "No note for today yet. Tap to create."
    const val PICKER_TITLE = "Pick a note"
}
