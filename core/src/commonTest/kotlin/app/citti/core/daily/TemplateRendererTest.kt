package app.citti.core.daily

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

class TemplateRendererTest {
    private val fr = DateLocales.fr
    private val en = DateLocales.en
    private val now = LocalTime(9, 7, 5)

    private fun render(
        template: String,
        date: LocalDate = LocalDate(2026, 10, 31),
        format: String = "YYYY-MM-DD",
        locale: DateLocale = en,
        at: LocalTime = now,
    ): String = TemplateRenderer.render(template, date, at, format, locale)

    @Test
    fun theVaultTemplateGivesTheFirstLineObsidianWrote() {
        // Line 1 of the vault's journal/2026-10-31.md and journal/2026-11-05.md, made by Obsidian from DAILY.
        val cases =
            mapOf(
                LocalDate(2026, 10, 31) to
                    "# 2026-10-31  [[journal/2026-W44|Week 44]] | [[journal/2026-octobre|octobre]] | " +
                    "[[journal/2026-Q4|Q4]] | [[journal/2026|2026]]",
                LocalDate(2026, 11, 5) to
                    "# 2026-11-05  [[journal/2026-W45|Week 45]] | [[journal/2026-novembre|novembre]] | " +
                    "[[journal/2026-Q4|Q4]] | [[journal/2026|2026]]",
            )
        for ((date, firstLine) in cases) {
            val rendered = render(DAILY, date = date, locale = fr)
            assertEquals(firstLine, rendered.substringBefore('\n'))
            assertEquals(DAILY.substring(DAILY.indexOf('\n')), rendered.substring(rendered.indexOf('\n')))
        }
    }

    @Test
    fun dateAndTitleGiveTheTitleInTheDateFormat() {
        assertEquals("31.10.2026 / 31.10.2026", render("{{date}} / {{title}}", format = "DD.MM.YYYY"))
        assertEquals(
            "samedi 31 octobre 2026",
            render("{{ Title }}", format = "dddd D MMMM YYYY", locale = fr),
        )
    }

    @Test
    fun timeGivesTheCurrentTime() {
        assertEquals("at 09:07", render("at {{time}}"))
        assertEquals("09", render("{{time:HH}}"))
        assertEquals("2026-10-31 09:07:05", render("{{date:YYYY-MM-DD HH:mm:ss}}"))
    }

    @Test
    fun placeholdersIgnoreCaseAndSpaces() {
        assertEquals("2026", render("{{ DATE : YYYY }}"))
        assertEquals("2026-10-31", render("{{ Date }}"))
        assertEquals("09:07", render("{{ TIME }}"))
    }

    @Test
    fun offsetsMoveTheDateLikeMomentAdd() {
        assertEquals("01", render("{{date+1d:DD}}"))
        assertEquals("septembre", render("{{date-1M:MMMM}}", locale = fr))
        assertEquals("11", render("{{date+2h:HH}}"))
        assertEquals("09:37", render("{{date+30m:HH:mm}}"))
        assertEquals("2026-11-01 01:07", render("{{date+16h:YYYY-MM-DD HH:mm}}"))
        assertEquals("2026-10-30 23:07", render("{{time-10h:YYYY-MM-DD HH:mm}}"))
        assertEquals("2026-11-07", render("{{date+1w:YYYY-MM-DD}}"))
        assertEquals("2027-01-31", render("{{date+1q:YYYY-MM-DD}}"))
        assertEquals("2025", render("{{date-1y:YYYY}}"))
        assertEquals("2026-11-30", render("{{date+1M:YYYY-MM-DD}}"))
        assertEquals("09:07:35", render("{{time+30s:HH:mm:ss}}"))
        // Without a format, the Date format, as in the plugin.
        assertEquals("2026-11-01", render("{{date+1d}}"))
    }

    @Test
    fun yesterdayAndTomorrowCrossAMonthEnd() {
        assertEquals("2026-10-30 2026-11-01", render("{{yesterday}} {{ Tomorrow }}"))
        assertEquals("2026-02-28 2026-03-02", render("{{yesterday}} {{tomorrow}}", date = LocalDate(2026, 3, 1)))
    }

    @Test
    fun anythingElseStaysAsTyped() {
        val kept = "<% tp.date.now() %> {{cursor}} {{unknown}} {{ lone {{date+1x:DD}} }} {date} {{date"
        assertEquals(kept, render(kept))
        assertEquals("a {{ 2026-10-31", render("a {{ {{date}}"))
    }

    @Test
    fun theStepsRunInThePluginOrder() {
        // Each step sees the output of the previous ones, as the plugin's chained replace calls do.
        assertEquals("31", render("{{title}}", format = "[{{date:DD}}]"))
    }

    @Test
    fun anEmptyTemplateGivesAnEmptyNote() {
        assertEquals("", render(""))
    }

    private companion object {
        /** The vault's templates/daily.md, byte for byte (two spaces after the title, a trailing blank line). */
        const val DAILY =
            "# {{title}}  [[journal/{{date:YYYY}}-W{{date:ww}}|Week {{date:ww}}]] | " +
                "[[journal/{{date:YYYY}}-{{date:MMMM}}|{{date:MMMM}}]] | " +
                "[[journal/{{date:YYYY}}-Q{{date:Q}}|Q{{date:Q}}]] | " +
                "[[journal/{{date:YYYY}}|{{date:YYYY}}]]\n" +
                "\n---\n\n\n\n---\n\n"
    }
}
