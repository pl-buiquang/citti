package app.citti.core.daily

import app.citti.core.settings.DEFAULT_DAILY_FORMAT
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus

/** The kinds of note the Journal folder holds by date: a Daily note, then the Periodic notes. */
enum class PeriodKind(
    /** The format of its titles until the user changes it. */
    val defaultFormat: String,
) {
    DAY(DEFAULT_DAILY_FORMAT),
    WEEK("GGGG-[W]WW"),
    MONTH("YYYY-MMMM"),
    QUARTER("YYYY-[Q]Q"),
    YEAR("YYYY"),
    ;

    /** The Periodic note kinds: every kind but [DAY]. */
    companion object {
        val periodic: List<PeriodKind> = entries.filter { it != DAY }
    }
}

/**
 * A day, week, month, quarter or year, by the date it starts on ([of] makes it): a Monday for a week (ISO), the 1st
 * for a month, the first month's 1st for a quarter, 1 January for a year. Its note's title is [start] formatted with
 * the kind's format, so any date of the period gives the same note.
 */
data class Period(
    val kind: PeriodKind,
    val start: LocalDate,
) {
    /** The next period of the same kind. */
    fun next(): Period = Period(kind, start.plus(1.step(), unit()))

    /** The previous period of the same kind. */
    fun previous(): Period = Period(kind, start.minus(1.step(), unit()))

    /** The period of the same kind that holds the date one year after [start]. */
    fun nextYear(): Period = of(kind, start.plus(1, DateTimeUnit.YEAR))

    /** The last day of the period. */
    val end: LocalDate get() = next().start.minus(1, DateTimeUnit.DAY)

    /** True once [today] is after the period: it can't be created by accident, so asking first is worth it. */
    fun isOver(today: LocalDate): Boolean = end < today

    private fun Int.step(): Int =
        when (kind) {
            PeriodKind.DAY, PeriodKind.MONTH, PeriodKind.YEAR -> this
            PeriodKind.WEEK -> this * DAYS_IN_WEEK
            PeriodKind.QUARTER -> this * MONTHS_IN_QUARTER
        }

    private fun unit(): DateTimeUnit.DateBased =
        when (kind) {
            PeriodKind.DAY, PeriodKind.WEEK -> DateTimeUnit.DAY
            PeriodKind.MONTH, PeriodKind.QUARTER -> DateTimeUnit.MONTH
            PeriodKind.YEAR -> DateTimeUnit.YEAR
        }

    companion object {
        private const val DAYS_IN_WEEK = 7
        private const val MONTHS_IN_QUARTER = 3

        /** The [kind] period that holds [date]. */
        fun of(
            kind: PeriodKind,
            date: LocalDate,
        ): Period =
            Period(
                kind,
                when (kind) {
                    PeriodKind.DAY -> date
                    PeriodKind.WEEK -> date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
                    PeriodKind.MONTH -> LocalDate(date.year, date.month.number, 1)
                    PeriodKind.QUARTER -> LocalDate(date.year, (date.month.number - 1) / MONTHS_IN_QUARTER * MONTHS_IN_QUARTER + 1, 1)
                    PeriodKind.YEAR -> LocalDate(date.year, 1, 1)
                },
            )
    }
}
