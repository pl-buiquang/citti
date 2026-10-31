package app.citti.core.daily

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Fills in a Daily note's Template with Obsidian's core Daily notes syntax, the same as `createDailyNote` in
 * `obsidian-daily-notes-interface`, in its order:
 *
 * 1. `{{date}}`, then `{{time}}` (the current time as `HH:mm`), then `{{title}}`; `{{date}}` and `{{title}}` give the
 *    note title, [date] in the Date format.
 * 2. `{{date[±N unit][:FMT]}}` and `{{time[±N unit][:FMT]}}`: [date] with the current time of day, moved by the
 *    offset, in FMT (trimmed), or in the Date format without one. The units are `y q M w d h m s`, where lowercase
 *    `m` means minutes and uppercase `M` months, as in Moment's `add`.
 * 3. `{{yesterday}}` and `{{tomorrow}}`: that date in the Date format.
 *
 * Placeholders are case-insensitive and may contain spaces. Everything outside a match, Templater `<% %>` included,
 * stays exactly as typed, and the text is expanded once.
 */
object TemplateRenderer {
    private val DATE = Regex("""\{\{\s*date\s*\}\}""", RegexOption.IGNORE_CASE)
    private val TIME = Regex("""\{\{\s*time\s*\}\}""", RegexOption.IGNORE_CASE)
    private val TITLE = Regex("""\{\{\s*title\s*\}\}""", RegexOption.IGNORE_CASE)
    private val DATE_OR_TIME =
        Regex("""\{\{\s*(date|time)\s*(([+-]\d+)([yqmwdhs]))?\s*(:.+?)?\}\}""", RegexOption.IGNORE_CASE)
    private val YESTERDAY = Regex("""\{\{\s*yesterday\s*\}\}""", RegexOption.IGNORE_CASE)
    private val TOMORROW = Regex("""\{\{\s*tomorrow\s*\}\}""", RegexOption.IGNORE_CASE)

    private const val SECONDS_PER_DAY = 86_400L

    /**
     * [template] filled in for the Daily note of [date], created at [now] (local time), with the Date [format] and
     * the Date [locale].
     */
    fun render(
        template: String,
        date: LocalDate,
        now: LocalTime,
        format: String,
        locale: DateLocale,
    ): String {
        if (template.isEmpty()) return ""
        val title = MomentFormat.format(date, now, format, locale)
        val clock = MomentFormat.format(date, now, "HH:mm", locale)
        val at = LocalDateTime(date, LocalTime(now.hour, now.minute, now.second))
        return template
            .replace(DATE) { title }
            .replace(TIME) { clock }
            .replace(TITLE) { title }
            .replace(DATE_OR_TIME) { match -> dateOrTime(match, at, format, locale) }
            .replace(YESTERDAY) { MomentFormat.format(date.minus(1, DateTimeUnit.DAY), now, format, locale) }
            .replace(TOMORROW) { MomentFormat.format(date.plus(1, DateTimeUnit.DAY), now, format, locale) }
    }

    private fun dateOrTime(
        match: MatchResult,
        at: LocalDateTime,
        format: String,
        locale: DateLocale,
    ): String {
        val (_, calc, delta, unit, momentFormat) = match.destructured
        val moved =
            if (calc.isEmpty()) {
                at
            } else {
                // An offset too large for a date is left as typed rather than failing the creation.
                delta.removePrefix("+").toIntOrNull()?.let { amount -> runCatching { add(at, amount, unit) }.getOrNull() }
                    ?: return match.value
            }
        val pattern = if (momentFormat.isEmpty()) format else momentFormat.substring(1).trim()
        return MomentFormat.format(moved.date, moved.time, pattern, locale)
    }

    /** Moment's `add(amount, unit)` on a local date-time: months clamp to the month's last day, as in Moment. */
    private fun add(
        at: LocalDateTime,
        amount: Int,
        unit: String,
    ): LocalDateTime =
        when (unit) {
            "M" -> {
                LocalDateTime(at.date.plus(amount, DateTimeUnit.MONTH), at.time)
            }

            "m" -> {
                addSeconds(at, amount * 60L)
            }

            else -> {
                when (unit.lowercase()) {
                    "y" -> LocalDateTime(at.date.plus(amount, DateTimeUnit.YEAR), at.time)
                    "q" -> LocalDateTime(at.date.plus(amount, DateTimeUnit.QUARTER), at.time)
                    "w" -> LocalDateTime(at.date.plus(amount, DateTimeUnit.WEEK), at.time)
                    "d" -> LocalDateTime(at.date.plus(amount, DateTimeUnit.DAY), at.time)
                    "h" -> addSeconds(at, amount * 3_600L)
                    else -> addSeconds(at, amount.toLong())
                }
            }
        }

    private fun addSeconds(
        at: LocalDateTime,
        seconds: Long,
    ): LocalDateTime {
        val total = at.time.toSecondOfDay() + seconds
        val days = total.floorDiv(SECONDS_PER_DAY)
        val time = LocalTime.fromSecondOfDay(total.mod(SECONDS_PER_DAY).toInt())
        return LocalDateTime(at.date.plus(days, DateTimeUnit.DAY), time)
    }
}
