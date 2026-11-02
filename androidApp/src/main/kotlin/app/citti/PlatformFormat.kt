package app.citti

import app.citti.ui.browse.RowDateFormatter
import app.citti.ui.browse.TitleCollator
import app.citti.ui.daily.PhoneCalendarLabels
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.toJavaDayOfWeek
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaYearMonth
import kotlinx.datetime.toKotlinDayOfWeek
import java.text.Collator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
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

/** Note info's last modified: an absolute date and time ("31 Oct 2026, 17:33") in the device zone and locale. */
class JavaTimeDateTimeFormatter(
    locale: Locale = Locale.getDefault(),
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val formatter =
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)

    fun format(epochSec: Long): String = formatter.format(Instant.ofEpochSecond(epochSec).atZone(zone()))
}

/**
 * The Calendar's labels in the phone's locale: stand-alone month names ("October 2026", "octobre 2026" titled
 * "Octobre 2026"), narrow weekday initials and the locale's first day of the week.
 */
class JavaTimeCalendarLabels(
    private val locale: Locale = Locale.getDefault(),
) : PhoneCalendarLabels {
    private companion object {
        const val CHECK_YEAR = 2026
    }

    private val monthYear = DateTimeFormatter.ofPattern("LLLL yyyy", locale)
    private val monthName = DateTimeFormatter.ofPattern("LLLL", locale)
    private val shortDate = DateTimeFormatter.ofPattern("EEE d MMM", locale)
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", locale)
    private val dayMonthLong = DateTimeFormatter.ofPattern("d MMMM", locale)

    override fun monthYear(month: YearMonth): String = monthYear.format(month.toJavaYearMonth()).replaceFirstChar { it.titlecase(locale) }

    override fun monthName(month: YearMonth): String = monthName.format(month.toJavaYearMonth())

    override fun monthTitle(month: YearMonth): String = monthName(month).replaceFirstChar { it.titlecase(locale) }

    // "octobre 2026" writes the month first, "2026年10月" the year.
    override val monthFirst: Boolean = !monthYear.format(java.time.YearMonth.of(CHECK_YEAR, 10)).startsWith(CHECK_YEAR.toString())

    override fun weekdayInitial(day: DayOfWeek): String = day.toJavaDayOfWeek().getDisplayName(TextStyle.NARROW_STANDALONE, locale)

    override fun weekdayName(day: DayOfWeek): String = day.toJavaDayOfWeek().getDisplayName(TextStyle.FULL_STANDALONE, locale)

    override fun defaultWeekStart(): DayOfWeek = WeekFields.of(locale).firstDayOfWeek.toKotlinDayOfWeek()

    override fun shortDate(date: LocalDate): String = shortDate.format(date.toJavaLocalDate())

    override fun dayMonth(date: LocalDate): String = dayMonth.format(date.toJavaLocalDate())

    override fun dayMonthLong(date: LocalDate): String = dayMonthLong.format(date.toJavaLocalDate())
}
