package app.citti.core.daily

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number

/**
 * Moment.js date formatting and strict parsing, for Daily note titles and Template dates (Obsidian formats dates with
 * Moment).
 *
 * The tokens are `YYYY YY MMMM MMM MM M DD D dddd ddd d ww w WW W Q HH mm ss`, longest first. `[literal]` and a
 * backslash before a token are escapes, as in Moment; every other character is kept as is. `ww`/`w` follow the
 * [DateLocale]'s week rule, `WW`/`W` are ISO weeks, and the year tokens are calendar years (`YYYY`, not `gggg`), so
 * `2027-01-01` in fr gives `2027-W53`, exactly like Moment. `Q` is the quarter 1–4 and `d` the weekday number with
 * 0 = Sunday.
 */
object MomentFormat {
    /** [date] and [time] in [pattern], with [locale]'s names and week rule. */
    fun format(
        date: LocalDate,
        time: LocalTime,
        pattern: String,
        locale: DateLocale,
    ): String {
        val out = StringBuilder()
        for (part in parts(pattern)) {
            when (part) {
                is Part.Literal -> out.append(part.text)
                is Part.Field -> out.append(render(part.token, date, time, pattern, locale))
            }
        }
        return out.toString()
    }

    /**
     * The date [text] spells in [pattern], or null. Strict: the whole text must match, names come from [locale]'s
     * tables (case-insensitive), a year, a month and a day are needed (`YY` means 20YY), and formatting the date
     * again must give exactly [text]. Tokens with nothing to parse back (`ww`, `Q`, `dddd`…) are only checked by
     * that round trip.
     */
    fun parse(
        text: String,
        pattern: String,
        locale: DateLocale,
    ): LocalDate? {
        val matcher = matcherFor(pattern, locale)
        val match = matcher.regex.matchEntire(text) ?: return null
        var year: Int? = null
        var month: Int? = null
        var day: Int? = null
        var hour = 0
        var minute = 0
        var second = 0
        matcher.tokens.forEachIndexed { i, token ->
            val value = match.groupValues[i + 1]
            when (token) {
                Token.YEAR -> year = value.toInt()
                Token.YEAR_2 -> year = 2000 + value.toInt()
                Token.MONTH_NAME -> month = locale.months.indexOfIgnoreCase(value) + 1
                Token.MONTH_SHORT -> month = locale.monthsShortFor(pattern).indexOfIgnoreCase(value) + 1
                Token.MONTH_2, Token.MONTH -> month = value.toInt()
                Token.DAY_2, Token.DAY -> day = value.toInt()
                Token.HOUR_2 -> hour = value.toInt()
                Token.MINUTE_2 -> minute = value.toInt()
                Token.SECOND_2 -> second = value.toInt()
                else -> Unit
            }
        }
        val date = dateOrNull(year ?: return null, month ?: return null, day ?: return null) ?: return null
        val time = timeOrNull(hour, minute, second) ?: return null
        return date.takeIf { format(it, time, pattern, locale) == text }
    }

    /**
     * True when [pattern] writes something that depends on the Date locale: a month or weekday name, or the locale
     * week (`ww`, `w`). A pattern without them gives the same text in every Date locale.
     */
    fun usesLocale(pattern: String): Boolean = parts(pattern).any { it is Part.Field && it.token in localeTokens }

    private val localeTokens =
        setOf(Token.MONTH_NAME, Token.MONTH_SHORT, Token.WEEKDAY_NAME, Token.WEEKDAY_SHORT, Token.WEEK_2, Token.WEEK)

    /** Moment's `weekOfYear(mom, dow, doy)`, ported as is: the week number and the year that week belongs to. */
    internal fun weekOfYear(
        date: LocalDate,
        dow: Int,
        doy: Int,
    ): WeekOfYear {
        val weekOffset = firstWeekOffset(date.year, dow, doy)
        val week = (date.dayOfYear - weekOffset - 1).floorDiv(7) + 1
        return when {
            week < 1 -> WeekOfYear(week + weeksInYear(date.year - 1, dow, doy), date.year - 1)
            week > weeksInYear(date.year, dow, doy) -> WeekOfYear(week - weeksInYear(date.year, dow, doy), date.year + 1)
            else -> WeekOfYear(week, date.year)
        }
    }

    internal data class WeekOfYear(
        val week: Int,
        val year: Int,
    )

    private const val ISO_DOW = 1
    private const val ISO_DOY = 4

    private fun firstWeekOffset(
        year: Int,
        dow: Int,
        doy: Int,
    ): Int {
        // The day of January that is always in week 1 (4 for ISO, 1 for en), and its weekday counted from dow.
        val fwd = 7 + dow - doy
        val fwdlw = (7 + LocalDate(year, 1, fwd).momentDay() - dow) % 7
        return -fwdlw + fwd - 1
    }

    private fun weeksInYear(
        year: Int,
        dow: Int,
        doy: Int,
    ): Int = (daysInYear(year) - firstWeekOffset(year, dow, doy) + firstWeekOffset(year + 1, dow, doy)) / 7

    private fun daysInYear(year: Int): Int = if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 366 else 365

    /** Moment's `day()`: 0 = Sunday … 6 = Saturday. */
    private fun LocalDate.momentDay(): Int = dayOfWeek.isoDayNumber % 7

    private fun render(
        token: Token,
        date: LocalDate,
        time: LocalTime,
        pattern: String,
        locale: DateLocale,
    ): String {
        val month = date.month.number
        return when (token) {
            Token.YEAR -> date.year.pad(4)
            Token.YEAR_2 -> (date.year % 100).pad(2)
            Token.MONTH_NAME -> locale.months[month - 1]
            Token.MONTH_SHORT -> locale.monthsShortFor(pattern)[month - 1]
            Token.MONTH_2 -> month.pad(2)
            Token.MONTH -> month.toString()
            Token.DAY_2 -> date.day.pad(2)
            Token.DAY -> date.day.toString()
            Token.WEEKDAY_NAME -> locale.weekdays[date.momentDay()]
            Token.WEEKDAY_SHORT -> locale.weekdaysShort[date.momentDay()]
            Token.WEEKDAY_NUMBER -> date.momentDay().toString()
            Token.WEEK_2 -> weekOfYear(date, locale.dow, locale.doy).week.pad(2)
            Token.WEEK -> weekOfYear(date, locale.dow, locale.doy).week.toString()
            Token.ISO_WEEK_2 -> weekOfYear(date, ISO_DOW, ISO_DOY).week.pad(2)
            Token.ISO_WEEK -> weekOfYear(date, ISO_DOW, ISO_DOY).week.toString()
            Token.QUARTER -> ((month - 1) / 3 + 1).toString()
            Token.HOUR_2 -> time.hour.pad(2)
            Token.MINUTE_2 -> time.minute.pad(2)
            Token.SECOND_2 -> time.second.pad(2)
        }
    }

    private fun Int.pad(width: Int): String = toString().padStart(width, '0')

    private fun List<String>.indexOfIgnoreCase(name: String): Int = indexOfFirst { it.equals(name, ignoreCase = true) }

    private fun dateOrNull(
        year: Int,
        month: Int,
        day: Int,
    ): LocalDate? =
        try {
            LocalDate(year, month, day)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun timeOrNull(
        hour: Int,
        minute: Int,
        second: Int,
    ): LocalTime? =
        try {
            LocalTime(hour, minute, second)
        } catch (_: IllegalArgumentException) {
            null
        }

    private enum class Token(
        val text: String,
    ) {
        YEAR("YYYY"),
        YEAR_2("YY"),
        MONTH_NAME("MMMM"),
        MONTH_SHORT("MMM"),
        MONTH_2("MM"),
        MONTH("M"),
        DAY_2("DD"),
        DAY("D"),
        WEEKDAY_NAME("dddd"),
        WEEKDAY_SHORT("ddd"),
        WEEKDAY_NUMBER("d"),
        WEEK_2("ww"),
        WEEK("w"),
        ISO_WEEK_2("WW"),
        ISO_WEEK("W"),
        QUARTER("Q"),
        HOUR_2("HH"),
        MINUTE_2("mm"),
        SECOND_2("ss"),
    }

    private sealed interface Part {
        data class Literal(
            val text: String,
        ) : Part

        data class Field(
            val token: Token,
        ) : Part
    }

    private val tokensByText = Token.entries.associateBy { it.text }

    // Moment's formattingTokens regex, reduced to our tokens: a `[...]` escape, or an optional backslash and then the
    // longest token or any one character.
    private val tokenizer =
        Regex(
            """(\[[^\[]*])|(\\)?(""" +
                Token.entries.sortedByDescending { it.text.length }.joinToString("|") { it.text } +
                """|[\s\S])""",
        )

    private fun parts(pattern: String): List<Part> =
        tokenizer
            .findAll(pattern)
            .mapNotNull { match ->
                val bracket = match.groups[1]?.value
                val escaped = match.groups[2] != null
                val text = match.groups[3]?.value.orEmpty()
                val token = tokensByText[text]
                when {
                    bracket != null -> Part.Literal(bracket.substring(1, bracket.length - 1))
                    escaped -> Part.Literal(text)
                    token != null -> Part.Field(token)
                    text == "\\" -> null
                    else -> Part.Literal(text)
                }
            }.toList()

    private class Matcher(
        val pattern: String,
        val locale: DateLocale,
        val regex: Regex,
        val tokens: List<Token>,
    )

    // DailyIndex parses every Journal title with the same pattern: keep the last matcher.
    private var lastMatcher: Matcher? = null

    private fun matcherFor(
        pattern: String,
        locale: DateLocale,
    ): Matcher {
        lastMatcher?.let { if (it.pattern == pattern && it.locale == locale) return it }
        val tokens = mutableListOf<Token>()
        val source = StringBuilder()
        for (part in parts(pattern)) {
            when (part) {
                is Part.Literal -> {
                    source.append(Regex.escape(part.text))
                }

                is Part.Field -> {
                    tokens += part.token
                    source.append('(').append(groupFor(part.token, pattern, locale)).append(')')
                }
            }
        }
        return Matcher(pattern, locale, Regex(source.toString(), RegexOption.IGNORE_CASE), tokens).also {
            lastMatcher = it
        }
    }

    private fun groupFor(
        token: Token,
        pattern: String,
        locale: DateLocale,
    ): String =
        when (token) {
            Token.YEAR -> """\d{4}"""

            Token.MONTH_NAME -> alternatives(locale.months)

            Token.MONTH_SHORT -> alternatives(locale.monthsShortFor(pattern))

            Token.WEEKDAY_NAME -> alternatives(locale.weekdays)

            Token.WEEKDAY_SHORT -> alternatives(locale.weekdaysShort)

            Token.WEEKDAY_NUMBER -> "[0-6]"

            Token.QUARTER -> "[1-4]"

            Token.MONTH, Token.DAY, Token.WEEK, Token.ISO_WEEK -> """\d{1,2}"""

            Token.YEAR_2, Token.MONTH_2, Token.DAY_2, Token.WEEK_2, Token.ISO_WEEK_2, Token.HOUR_2, Token.MINUTE_2,
            Token.SECOND_2,
            -> """\d{2}"""
        }

    private fun alternatives(names: List<String>): String =
        names.distinct().sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
}
