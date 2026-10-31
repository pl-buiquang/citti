package app.citti.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** The first local midnight after [now] in [zone]: 00:00 exactly gives the next day's, and a 23 h or 25 h day is handled. */
fun nextMidnight(
    now: Instant,
    zone: TimeZone,
): Instant =
    now
        .toLocalDateTime(zone)
        .date
        .plus(1, DateTimeUnit.DAY)
        .atStartOfDayIn(zone)

/**
 * The inexact alarm that redraws the date of the Today row and the Daily note widget at local midnight. Each draw of
 * either widget sets it again (so a reboot, whose APPWIDGET_UPDATE redraws the widgets, does too); with no such widget
 * placed, nothing sets it. A 10 minute window needs no exact-alarm permission.
 */
object DayScheduler {
    private const val REQUEST_CODE = 7001
    private const val WINDOW_MS = 10 * 60 * 1000L

    fun schedule(context: Context) {
        val config = context.container().config
        val at = nextMidnight(config.clock.now(), TimeZone.of(config.timeZone().id))
        context
            .getSystemService(AlarmManager::class.java)
            .setWindow(AlarmManager.RTC, at.toEpochMilliseconds(), WINDOW_MS, pending(context))
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context))
    }

    private fun pending(context: Context) =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, DayChangeReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Cancels the alarm once neither the Favorites widget nor the Daily note widget is placed any more. */
    suspend fun cancelIfUnused(context: Context) {
        val manager = GlanceAppWidgetManager(context)
        val placed =
            manager.getGlanceIds(FavoritesWidget::class.java).isNotEmpty() ||
                manager.getGlanceIds(DailyNoteWidget::class.java).isNotEmpty()
        if (!placed) cancel(context)
    }
}

/** The midnight alarm, and the clock, zone and locale changes: redraw (which also sets the next alarm). */
class DayChangeReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                FavoritesWidget().updateAll(context)
                DailyNoteWidget().updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }
}
