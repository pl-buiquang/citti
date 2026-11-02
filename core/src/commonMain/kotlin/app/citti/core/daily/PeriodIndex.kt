package app.citti.core.daily

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.citti.core.db.CittiDatabase
import app.citti.core.settings.DailyConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * Finds the Periodic note a title belongs to. [MomentFormat.parse] can't do it: `MMMM`, `Q` and `WW` don't say which
 * year they are in, and `GGGG-[W]WW` doesn't say which day. So it goes the other way (ADR 0011): the years written
 * in the title are the candidates, every Week, Month, Quarter and Year of them is formatted with its kind's format,
 * and the period whose title is exactly the one asked for is the answer.
 */
internal object PeriodTitles {
    private val YEARS = Regex("""\d{4}|\d{2}""")

    /** The years a title may be about: each run of 4 digits, and each run of 2 digits as 20YY. */
    fun candidateYears(title: String): Set<Int> =
        YEARS
            .findAll(title)
            .map { it.value }
            .mapNotNull { run -> run.toIntOrNull()?.let { if (run.length == 2) 2000 + it else it } }
            .toSet()

    /**
     * Every [kind] period of [year]. For a week that is each week that starts from 20 December of the year before to
     * 10 January of the year after, so a week-year (`GGGG`) that differs from the calendar year is found too.
     */
    fun periodsOf(
        kind: PeriodKind,
        year: Int,
    ): List<Period> {
        val last = LocalDate(year + 1, 1, 10)
        return generateSequence(Period.of(kind, if (kind == PeriodKind.WEEK) LocalDate(year - 1, 12, 20) else LocalDate(year, 1, 1))) {
            it.next()
        }.takeWhile { if (kind == PeriodKind.WEEK) it.start <= last else it.start.year == year }
            .toList()
    }

    /** The Periodic note of [kind] [title] belongs to in [config], rendered in one of [locales]; null when none. */
    fun match(
        title: String,
        kind: PeriodKind,
        config: DailyConfig,
        locales: List<DateLocale>,
    ): Period? {
        val format = config.formatOf(kind)
        val rendering = if (MomentFormat.usesLocale(format)) locales else locales.take(1)
        for (year in candidateYears(title)) {
            for (period in periodsOf(kind, year)) {
                if (rendering.any { MomentFormat.format(period.start, LocalTime(0, 0), format, it).equals(title, ignoreCase = true) }) {
                    return period
                }
            }
        }
        return null
    }

    /** The Periodic note [title] is the title of in [config], or null. */
    fun match(
        title: String,
        config: DailyConfig,
        locales: List<DateLocale>,
    ): Period? = PeriodKind.periodic.firstNotNullOfOrNull { match(title, it, config, locales) }

    /** The title of [period] in [config], rendered in [locale]. */
    fun titleOf(
        period: Period,
        config: DailyConfig,
        locale: DateLocale,
    ): String = MomentFormat.format(period.start, LocalTime(0, 0), config.formatOf(period.kind), locale)
}

/**
 * The Periodic notes of the Journal folder by [Period], for the Calendar and the Note screen's previous and next
 * links: every Note of the folder whose title is a Week, Month, Quarter or Year note ([PeriodTitles]), Hidden ones
 * included, DELETED ones not. Parsed on [dispatcher].
 */
class PeriodIndex(
    db: CittiDatabase,
    private val locales: LocaleDetector,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val notes = db.noteQueries

    /** Follows the Journal folder of [config]. When two titles give the same period, the exact title wins. */
    fun flow(config: DailyConfig): Flow<Map<Period, DailyEntry>> {
        val reading = locales.readingLocales(config)
        return notes
            .selectJournalNotes(config.folder)
            .asFlow()
            .mapToList(dispatcher)
            .map { rows ->
                val byPeriod = HashMap<Period, Pair<String, DailyEntry>>()
                for (row in rows) {
                    // A title that is a Daily note's is not looked up again as a Periodic note's.
                    if (dailyDate(row.title, config.format, reading) != null) continue
                    val period = PeriodTitles.match(row.title, config, reading) ?: continue
                    val entry = DailyEntry(row.local_id, row.excerpt)
                    val previous = byPeriod[period]
                    if (previous == null || row.title == PeriodTitles.titleOf(period, config, reading.first())) {
                        byPeriod[period] = row.title to entry
                    }
                }
                byPeriod.mapValues { it.value.second }
            }.flowOn(dispatcher)
            .distinctUntilChanged()
    }
}

/** The nearest note of the same kind before and after [period]: empty periods are skipped. */
fun Map<Period, DailyEntry>.neighborsOf(period: Period): PeriodNeighbors {
    var previous: Period? = null
    var next: Period? = null
    for (p in keys) {
        if (p.kind != period.kind) continue
        if (p.start < period.start && (previous == null || p.start > previous.start)) previous = p
        if (p.start > period.start && (next == null || p.start < next.start)) next = p
    }
    return PeriodNeighbors(
        previous?.let { PeriodNeighbor(it, getValue(it).localId) },
        next?.let { PeriodNeighbor(it, getValue(it).localId) },
    )
}

/** A Periodic note next to another one. */
data class PeriodNeighbor(
    val period: Period,
    val localId: Long,
)

/** The Periodic notes right before and after a period. */
data class PeriodNeighbors(
    val previous: PeriodNeighbor?,
    val next: PeriodNeighbor?,
)
