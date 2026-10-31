package app.citti.widget

import android.content.Context
import android.text.format.DateFormat
import app.citti.AppContainer
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The device's local date, through the clock and zone seams of AppConfig (E2E pins them). */
internal fun AppContainer.todayDate(): LocalDate =
    config.clock
        .now()
        .toLocalDateTime(TimeZone.of(config.timeZone().id))
        .date

/** "Saturday 31 October" in the phone locale (the skeleton picks the locale's own order and punctuation). */
internal fun dateLabel(
    date: LocalDate,
    locale: Locale = Locale.getDefault(),
): String {
    val pattern = DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM")
    return DateTimeFormatter.ofPattern(pattern, locale).format(java.time.LocalDate.of(date.year, date.month.ordinal + 1, date.day))
}

internal fun Context.todayLabel(): String = dateLabel(container().todayDate())
