package app.citti.core.daily

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MomentFormatTest {
    private val noon = LocalTime(12, 0)

    @Test
    fun everyTokenInEveryLocaleMatchesMoment() {
        // In Moment, x, y and z are tokens too (Unix ms, era year, zone): citti keeps them as typed, so those rows
        // can't match. "bcf" is the unknown-letters case.
        val rows = expectedFormat.filterNot { it.pattern.endsWith(" xyz") }
        val failures =
            rows.mapNotNull { row ->
                val at = LocalDateTime.parse(row.dateTime)
                val actual = MomentFormat.format(at.date, at.time, row.pattern, DateLocales.forCode(row.locale))
                if (actual == row.expected) null else "$row -> $actual"
            }
        assertEquals(emptyList(), failures)
        assertEquals(7 * 2 * 26, rows.size)
    }

    @Test
    fun weeksAroundYearEndsMatchMoment() {
        val failures =
            expectedWeeks.mapNotNull { row ->
                val date = LocalDate.parse(row.date)
                val actual =
                    listOf("YYYY", "ww", "w", "WW", "W").map {
                        MomentFormat.format(date, noon, it, DateLocales.forCode(row.locale))
                    }
                val expected = listOf(row.year, row.ww, row.w, row.isoWw, row.isoW)
                if (actual == expected) null else "$row -> $actual"
            }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun yearTokensAreCalendarYears() =
        assertEquals("2027-W53", MomentFormat.format(LocalDate(2027, 1, 1), noon, "YYYY-[W]ww", DateLocales.fr))

    @Test
    fun weekYearTokensGiveTheYearOfTheWeek() {
        val fr = DateLocales.fr
        assertEquals("2026-W53", MomentFormat.format(LocalDate(2027, 1, 1), noon, "GGGG-[W]WW", fr))
        assertEquals("2025-W01", MomentFormat.format(LocalDate(2024, 12, 30), noon, "GGGG-[W]WW", fr))
        assertEquals("2024-W52", MomentFormat.format(LocalDate(2024, 12, 29), noon, "GGGG-[W]WW", fr))
        assertEquals("26-W53", MomentFormat.format(LocalDate(2027, 1, 1), noon, "GG-[W]WW", fr))
        assertEquals("2026-W53", MomentFormat.format(LocalDate(2027, 1, 1), noon, "gggg-[W]ww", fr))
        assertEquals("26-W53", MomentFormat.format(LocalDate(2027, 1, 1), noon, "gg-[W]ww", fr))
    }

    @Test
    fun theLocaleWeekYearFollowsTheLocaleRule() {
        // en weeks start on Sunday and week 1 holds 1 January: 2027-01-01 is week 1 of 2027, not week 53 of 2026.
        assertEquals("2027-W01", MomentFormat.format(LocalDate(2027, 1, 1), noon, "gggg-[W]ww", DateLocales.en))
        assertEquals("2026-W53", MomentFormat.format(LocalDate(2027, 1, 1), noon, "GGGG-[W]WW", DateLocales.en))
    }

    @Test
    fun onlyTheLocaleWeekYearDependsOnTheLocale() {
        assertEquals(false, MomentFormat.usesLocale("GGGG-[W]WW"))
        assertEquals(true, MomentFormat.usesLocale("gggg-[W]ww"))
    }

    @Test
    fun bracketsAndBackslashesEscape() {
        val date = LocalDate(2026, 10, 31)
        assertEquals("Week 44 of YYYY", MomentFormat.format(date, noon, "[Week] ww [of YYYY]", DateLocales.en))
        assertEquals("YYYY-10", MomentFormat.format(date, noon, "\\YYYY-MM", DateLocales.en))
        assertEquals("2026 ", MomentFormat.format(date, noon, "YYYY \\", DateLocales.en))
        assertEquals("[x]", MomentFormat.format(date, noon, "[[x]]", DateLocales.en))
    }

    @Test
    fun unknownLettersAndPunctuationAreKept() =
        assertEquals(
            "2026-10-31 bcf, é!",
            MomentFormat.format(LocalDate(2026, 10, 31), noon, "YYYY-MM-DD bcf, é!", DateLocales.en),
        )

    @Test
    fun dashedShortMonthsDropTheDotInEsAndNl() {
        val date = LocalDate(2026, 10, 31)
        assertEquals("oct.", MomentFormat.format(date, noon, "MMM", DateLocales.es))
        assertEquals("31-oct-2026", MomentFormat.format(date, noon, "DD-MMM-YYYY", DateLocales.es))
        // The rule looks at the whole pattern, as Moment's /-MMM-/.test(format) does.
        assertEquals("31-okt-2026 okt", MomentFormat.format(date, noon, "DD-MMM-YYYY MMM", DateLocales.nl))
        assertEquals("okt.", MomentFormat.format(date, noon, "MMM", DateLocales.nl))
        assertEquals("31-oct.-2026", MomentFormat.format(date, noon, "DD-MMM-YYYY", DateLocales.fr))
    }

    @Test
    fun parseRoundTripsRandomDatesInEveryLocale() {
        val random = Random(SEED)
        val first = LocalDate(1900, 1, 1).toEpochDays()
        val last = LocalDate(2199, 12, 31).toEpochDays()
        val failures = mutableListOf<String>()
        for (locale in DateLocales.all) {
            for (pattern in ROUND_TRIP_PATTERNS) {
                repeat(2_000) {
                    val date = LocalDate.fromEpochDays(random.nextLong(first, last + 1))
                    val text = MomentFormat.format(date, noon, pattern, locale)
                    val parsed = MomentFormat.parse(text, pattern, locale)
                    if (parsed != date) failures += "${locale.code} $pattern $text -> $parsed"
                }
            }
        }
        assertEquals(emptyList(), failures.take(10))
    }

    @Test
    fun parseReadsNamesAndTwoDigitYears() {
        assertEquals(LocalDate(2026, 10, 31), MomentFormat.parse("31 octobre 2026", "D MMMM YYYY", DateLocales.fr))
        assertEquals(LocalDate(2026, 10, 31), MomentFormat.parse("31-oct-2026", "DD-MMM-YYYY", DateLocales.es))
        assertEquals(LocalDate(2026, 10, 31), MomentFormat.parse("31.10.26", "DD.MM.YY", DateLocales.de))
        assertEquals(
            LocalDate(2026, 10, 31),
            MomentFormat.parse("2026-10-31 Week 44 Q4", "YYYY-MM-DD [Week] ww [Q]Q", DateLocales.fr),
        )
    }

    @Test
    fun parseRejectsTitlesThatAreNotThatDate() {
        val rejected =
            listOf(
                "2026-10-31 (conflict 2026-11-02)",
                "2026-W44",
                "2026-octobre",
                "2026-Q4",
                "2026",
                "2026-10-31 (2)",
                "2026-02-30",
            )
        for (title in rejected) {
            for (locale in DateLocales.all) assertNull(MomentFormat.parse(title, "YYYY-MM-DD", locale), title)
        }
    }

    @Test
    fun parseChecksTheRoundTrip() {
        // A wrong weekday, a wrong week number, a different case or an unpadded day all fail the round trip.
        assertNull(MomentFormat.parse("2026-10-31 Friday", "YYYY-MM-DD dddd", DateLocales.en))
        assertNull(MomentFormat.parse("2026-10-31 Week 43", "YYYY-MM-DD [Week] ww", DateLocales.en))
        assertNull(MomentFormat.parse("31 Octobre 2026", "D MMMM YYYY", DateLocales.fr))
        assertNull(MomentFormat.parse("2026-10-1", "YYYY-MM-DD", DateLocales.en))
        assertNull(MomentFormat.parse("31-oct.-2026", "DD-MMM-YYYY", DateLocales.es))
    }

    @Test
    fun parseNeedsAYearAMonthAndADay() {
        assertNull(MomentFormat.parse("2026-10", "YYYY-MM", DateLocales.en))
        assertNull(MomentFormat.parse("2026-W44", "YYYY-[W]WW", DateLocales.en))
        assertNull(MomentFormat.parse("10-31", "MM-DD", DateLocales.en))
    }

    @Test
    fun weekdayNumberAndQuarter() {
        assertEquals("0 1", MomentFormat.format(LocalDate(2026, 1, 4), noon, "d Q", DateLocales.fr))
        assertEquals("6 4", MomentFormat.format(LocalDate(2026, 12, 26), noon, "d Q", DateLocales.fr))
    }

    @Test
    fun usesLocaleSpotsNamesAndLocaleWeeks() {
        listOf("YYYY-MM-DD", "DD.MM.YYYY", "YYYY-[W]WW", "Q HH:mm", "[MMMM dddd ww]").forEach {
            assertEquals(false, MomentFormat.usesLocale(it), it)
        }
        listOf("D MMMM YYYY", "D MMM", "dddd", "ddd", "YYYY-[W]ww", "w").forEach {
            assertEquals(true, MomentFormat.usesLocale(it), it)
        }
    }

    @Test
    fun unknownCodesFallBackToEn() {
        assertEquals(DateLocales.en, DateLocales.forCode("ja"))
        assertEquals(DateLocales.en, DateLocales.forCode(null))
        assertEquals(DateLocales.nl, DateLocales.forCode("nl"))
    }

    private companion object {
        const val SEED = 20261031
        val ROUND_TRIP_PATTERNS = listOf("YYYY-MM-DD", "DD.MM.YYYY", "YYYY-MM-DD dddd", "D MMMM YYYY")
    }
}
