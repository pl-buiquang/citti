package app.citti.ui.browse

import androidx.compose.runtime.Composable
import app.citti.ui.resources.Res
import app.citti.ui.resources.date_days
import app.citti.ui.resources.date_hours
import app.citti.ui.resources.date_minutes
import app.citti.ui.resources.date_now
import app.citti.ui.resources.date_yesterday
import org.jetbrains.compose.resources.stringResource

/** Orders titles for Tree mode, in the phone's locale. The app provides it: commonMain has no `java.text`. */
fun interface TitleCollator : Comparator<String>

/**
 * The calendar side of a row date, in the device time zone and locale. The app provides it (`java.time`), so
 * commonMain stays free of platform time and the tests stay deterministic.
 */
interface RowDateFormatter {
    /** The calendar day of [epochSec] in the device time zone, as days since 1970-01-01. */
    fun localDay(epochSec: Long): Long

    /** The calendar year of [epochSec] in the device time zone. */
    fun year(epochSec: Long): Int

    /** "12 Oct", or "12 Oct 2025" with [withYear]. */
    fun absolute(
        epochSec: Long,
        withYear: Boolean,
    ): String
}

/** What a row shows as its date, before the relative cases are turned into text. */
sealed interface RowDate {
    data object Now : RowDate

    data class Minutes(
        val minutes: Int,
    ) : RowDate

    data class Hours(
        val hours: Int,
    ) : RowDate

    /** The previous calendar day, not "24 to 48 h ago". */
    data object Yesterday : RowDate

    data class Days(
        val days: Int,
    ) : RowDate

    data class Absolute(
        val text: String,
    ) : RowDate
}

/**
 * `now`, `2 min`, `1 h` (same day), `Yest.`, `3 d` (under 7 days), then `12 Oct` (same year), then
 * `12 Oct 2025`. [modified] and [now] are epoch seconds; a [modified] in the future counts as now.
 */
fun rowDate(
    modified: Long,
    now: Long,
    calendar: RowDateFormatter,
): RowDate {
    val seconds = now - modified
    if (seconds < 60) return RowDate.Now
    if (seconds < 3600) return RowDate.Minutes((seconds / 60).toInt())
    val days = calendar.localDay(now) - calendar.localDay(modified)
    return when {
        days <= 0 -> RowDate.Hours((seconds / 3600).toInt())
        days == 1L -> RowDate.Yesterday
        days < 7 -> RowDate.Days(days.toInt())
        else -> RowDate.Absolute(calendar.absolute(modified, withYear = calendar.year(modified) != calendar.year(now)))
    }
}

@Composable
fun rowDateText(
    modified: Long,
    now: Long,
    calendar: RowDateFormatter,
): String =
    when (val date = rowDate(modified, now, calendar)) {
        RowDate.Now -> stringResource(Res.string.date_now)
        is RowDate.Minutes -> stringResource(Res.string.date_minutes, date.minutes)
        is RowDate.Hours -> stringResource(Res.string.date_hours, date.hours)
        RowDate.Yesterday -> stringResource(Res.string.date_yesterday)
        is RowDate.Days -> stringResource(Res.string.date_days, date.days)
        is RowDate.Absolute -> date.text
    }
