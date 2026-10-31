package app.citti

import app.citti.ui.browse.RowDateFormatter
import app.citti.ui.browse.TitleCollator
import java.text.Collator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Titles in the phone's locale, ignoring case and accents (`PRIMARY` strength). */
fun localeTitleCollator(locale: Locale = Locale.getDefault()): TitleCollator {
    val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
    return TitleCollator { a, b -> collator.compare(a, b) }
}

/** Row dates in the device time zone, read on every call so that a zone change applies at once. */
class JavaTimeRowDateFormatter(
    locale: Locale = Locale.getDefault(),
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : RowDateFormatter {
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", locale)
    private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", locale)

    private fun date(epochSec: Long) = Instant.ofEpochSecond(epochSec).atZone(zone()).toLocalDate()

    override fun localDay(epochSec: Long): Long = date(epochSec).toEpochDay()

    override fun year(epochSec: Long): Int = date(epochSec).year

    override fun absolute(
        epochSec: Long,
        withYear: Boolean,
    ): String = (if (withYear) dayMonthYear else dayMonth).format(date(epochSec))
}
