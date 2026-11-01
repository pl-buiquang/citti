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

/** One Daily note of the Calendar. */
data class DailyEntry(
    val localId: Long,
    /** Null until the Note's text is local. */
    val excerpt: String?,
)

/** A Daily note next to another one, on [date]. */
data class DailyNeighbor(
    val date: LocalDate,
    val localId: Long,
)

/** The Daily notes right before and after a date: the nearest dates that have a note, so empty days are skipped. */
data class DailyNeighbors(
    val previous: DailyNeighbor?,
    val next: DailyNeighbor?,
)

fun Map<LocalDate, DailyEntry>.neighborsOf(date: LocalDate): DailyNeighbors {
    var previous: LocalDate? = null
    var next: LocalDate? = null
    for (d in keys) {
        if (d < date && (previous == null || d > previous)) previous = d
        if (d > date && (next == null || d < next)) next = d
    }
    return DailyNeighbors(
        previous?.let { DailyNeighbor(it, getValue(it).localId) },
        next?.let { DailyNeighbor(it, getValue(it).localId) },
    )
}

/**
 * The Daily notes of the Journal folder by date, for the Calendar: every Note of the folder whose title is a Daily
 * note ([dailyDate]), Hidden ones included (CLAUDE.md), DELETED ones not. Parsed on [dispatcher].
 */
class DailyIndex(
    db: CittiDatabase,
    private val locales: LocaleDetector,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val notes = db.noteQueries

    /** Follows the Journal folder of [config]. When two titles give the same date, the exact title of the date wins. */
    fun flow(config: DailyConfig): Flow<Map<LocalDate, DailyEntry>> {
        val reading = locales.readingLocales(config)
        return notes
            .selectJournalNotes(config.folder)
            .asFlow()
            .mapToList(dispatcher)
            .map { rows ->
                val byDate = HashMap<LocalDate, Pair<String, DailyEntry>>()
                for (row in rows) {
                    val date = dailyDate(row.title, config.format, reading) ?: continue
                    val entry = DailyEntry(row.local_id, row.excerpt)
                    val previous = byDate[date]
                    if (previous == null || row.title == exactTitle(date, config, reading)) byDate[date] = row.title to entry
                }
                byDate.mapValues { it.value.second }
            }.flowOn(dispatcher)
            .distinctUntilChanged()
    }

    private fun exactTitle(
        date: LocalDate,
        config: DailyConfig,
        reading: List<DateLocale>,
    ) = MomentFormat.format(date, LocalTime(0, 0), config.format, reading.first())
}

/**
 * The date of the Daily note [title] in [format], or null when it isn't one: it parses in one of [locales] and
 * formatting that date again gives exactly [title] (so week, month and Conflict copy titles never match).
 */
internal fun dailyDate(
    title: String,
    format: String,
    locales: List<DateLocale>,
): LocalDate? = locales.firstNotNullOfOrNull { MomentFormat.parse(title, format, it) }
