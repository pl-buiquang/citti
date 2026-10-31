package app.citti.core.daily

import app.citti.core.db.CittiDatabase
import app.citti.core.search.foldedText
import app.citti.core.settings.DailyConfig
import app.citti.core.settings.DailySettings
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/** Notes a Date locale needs to collect before detection picks it. */
internal const val DETECTION_MIN_NOTES = 3

/** Notes detection reads at most. */
internal const val DETECTION_MAX_NOTES = 10

/**
 * Chooses the Date locale a Daily note is rendered with: the manual override, else the detected one, else detection
 * from the Journal folder's notes, else the phone's language when it is a Date locale, else en.
 *
 * Detection reads the first line of up to [DETECTION_MAX_NOTES] Daily notes (the newest of each month first) and
 * counts, per Date locale, the notes whose first line holds the month name of the note's own date as a whole word,
 * folded like search. A Date locale with at least [DETECTION_MIN_NOTES] notes wins; on a tie the phone's language
 * wins when it is among them, else nothing is decided. A decision is stored in `daily.locale-detected`, so detection
 * runs only until it decides. Blocking: call it off the main thread.
 *
 * [phoneLanguage] gives the phone's language (`fr`, `fr-FR`…).
 */
class LocaleDetector(
    db: CittiDatabase,
    private val settings: DailySettings,
    private val phoneLanguage: () -> String,
) {
    private val notes = db.noteQueries

    /** The Date locale to render a Daily note title or Template with (override → detected → detect → phone → en). */
    fun localeForRendering(config: DailyConfig): DateLocale {
        val code = config.locale ?: config.localeDetected ?: detect(config) ?: phoneLocale()?.code
        return DateLocales.forCode(code)
    }

    /** The Date locale code the Journal folder's notes are written in, stored on success; null without a decision. */
    internal fun detect(config: DailyConfig): String? {
        val dated =
            notes
                .selectJournalNotes(config.folder)
                .executeAsList()
                .mapNotNull { row ->
                    val line = row.first_line?.takeIf { row.has_content } ?: return@mapNotNull null
                    val date = dateOf(row.title, config.format) ?: return@mapNotNull null
                    DatedLine(date, line.removeSuffix("\r"))
                }.sortedByDescending { it.date }
        val counts = mutableMapOf<String, Int>()
        for (note in pick(dated)) {
            val line = foldedText(note.line)
            for (locale in DateLocales.all) {
                if (containsWord(line, foldedText(locale.months[note.date.month.number - 1]))) {
                    counts[locale.code] = (counts[locale.code] ?: 0) + 1
                }
            }
        }
        val best = counts.values.maxOrNull()?.takeIf { it >= DETECTION_MIN_NOTES } ?: return null
        val winners = counts.filterValues { it == best }.keys
        val code =
            when {
                winners.size == 1 -> winners.single()
                else -> phoneLocale()?.code?.takeIf { it in winners } ?: return null
            }
        settings.setLocaleDetected(code)
        return code
    }

    /** The phone's language as a Date locale, or null when citti has no Date locale for it. */
    private fun phoneLocale(): DateLocale? {
        val language = phoneLanguage().substringBefore('-').substringBefore('_').lowercase()
        return DateLocales.all.firstOrNull { it.code == language }
    }

    /**
     * The date a Daily note [title] spells in [format]. A format with names needs the right Date locale to parse, so
     * each one is tried (the phone's first); a numeric format parses the same in all of them.
     */
    private fun dateOf(
        title: String,
        format: String,
    ): LocalDate? {
        val phone = phoneLocale()
        val locales = listOfNotNull(phone) + DateLocales.all.filter { it != phone }
        return locales.firstNotNullOfOrNull { MomentFormat.parse(title, format, it) }
    }

    private class DatedLine(
        val date: LocalDate,
        val line: String,
    )

    private companion object {
        /**
         * At most [DETECTION_MAX_NOTES] of [newestFirst]: the newest note of each month, going back in time, then the
         * remaining newest ones, so a long month doesn't outweigh the rest of the journal.
         */
        fun pick(newestFirst: List<DatedLine>): List<DatedLine> {
            val firstOfMonth = newestFirst.distinctBy { it.date.year to it.date.month }.take(DETECTION_MAX_NOTES)
            val picked = firstOfMonth.toSet()
            return firstOfMonth + newestFirst.filter { it !in picked }.take(DETECTION_MAX_NOTES - firstOfMonth.size)
        }

        /** True when [word] appears in [text] with no letter right before or after it. */
        fun containsWord(
            text: String,
            word: String,
        ): Boolean {
            var from = 0
            while (true) {
                val at = text.indexOf(word, from)
                if (at < 0) return false
                val end = at + word.length
                val before = at > 0 && text[at - 1].isLetter()
                val after = end < text.length && text[end].isLetter()
                if (!before && !after) return true
                from = at + 1
            }
        }
    }
}
