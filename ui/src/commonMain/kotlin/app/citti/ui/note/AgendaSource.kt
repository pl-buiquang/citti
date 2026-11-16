package app.citti.ui.note

import app.citti.core.agenda.AgendaDisplay
import app.citti.core.agenda.AgendaRequest
import app.citti.core.agenda.AgendaSnapshot
import app.citti.core.agenda.ParsedOptions
import app.citti.core.agenda.parseAgendaOptions
import app.citti.core.agenda.resolveStartDate
import app.citti.core.daily.DateLocale
import app.citti.core.markdown.Block
import app.citti.core.markdown.Doc
import app.citti.core.settings.AgendaConfig
import app.citti.core.settings.EventCalendar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** What the Agenda blocks of a Note need from the app: Settings › Agenda, the events and the clock. */
interface AgendaSource {
    fun config(): Flow<AgendaConfig>

    /** The cached events of [request]; collecting refreshes the stale ones (ADR 0012). */
    fun events(request: AgendaRequest): Flow<AgendaSnapshot>

    /** The Refresh action of a block. */
    fun refresh(request: AgendaRequest)

    fun now(): Instant

    fun zone(): TimeZone

    /** The Date locale of the day and month names. */
    fun locale(): DateLocale
}

/** Why an Agenda block shows no events at all. */
sealed interface AgendaProblem {
    /** The `date` option is not a date. */
    data class InvalidDate(
        val value: String,
    ) : AgendaProblem

    /** No Event calendar is enabled in Settings › Agenda. */
    data object NoCalendar : AgendaProblem

    /** The `calendars` option names no enabled Event calendar. */
    data class NoMatch(
        val names: List<String>,
    ) : AgendaProblem
}

/** One Agenda block of the shown Note, with what is known of its events. */
data class AgendaBlockState(
    val options: ParsedOptions,
    val calendars: List<EventCalendar>,
    val display: AgendaDisplay,
    /** Set when nothing can be fetched. */
    val problem: AgendaProblem?,
    val request: AgendaRequest?,
    val snapshot: AgendaSnapshot?,
    val now: Instant,
    val zone: TimeZone,
    val locale: DateLocale,
)

/** The Agenda blocks of [doc]: the top-level ones (an Agenda block inside a quote stays a code block). */
internal fun agendaBlocks(doc: Doc): List<Block.Code> = doc.blocks.filterIsInstance<Block.Code>().filter { it.isAgenda }

/** What [block] asks for, without its events: the options, the calendars, the request. */
internal fun agendaState(
    block: Block.Code,
    noteTitle: String,
    config: AgendaConfig,
    source: AgendaSource,
): AgendaBlockState {
    val parsed = parseAgendaOptions(block.text)
    val options = parsed.options
    val zone = source.zone()
    val now = source.now()
    val today = now.toLocalDateTime(zone).date
    val start = resolveStartDate(options.date, noteTitle, today)
    val enabled = config.enabled
    val wanted = options.calendars
    val calendars = if (wanted == null) enabled else enabled.filter { c -> wanted.any { c.matches(it) } }
    val problem =
        when {
            start == null -> AgendaProblem.InvalidDate(options.date.orEmpty())
            enabled.isEmpty() -> AgendaProblem.NoCalendar
            calendars.isEmpty() -> AgendaProblem.NoMatch(wanted.orEmpty())
            else -> null
        }
    val request =
        if (problem == null && start != null) {
            AgendaRequest(calendars.map { it.href }, start, options.days ?: config.display.days, zone.id)
        } else {
            null
        }
    return AgendaBlockState(parsed, calendars, config.display, problem, request, null, now, zone, source.locale())
}

/** The states of every Agenda block of [doc], again whenever Settings › Agenda or the events change. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun agendaStates(
    doc: Doc,
    noteTitle: String,
    source: AgendaSource,
): Flow<Map<Int, AgendaBlockState>> {
    val blocks = agendaBlocks(doc)
    if (blocks.isEmpty()) return flowOf(emptyMap())
    return source.config().distinctUntilChanged().flatMapLatest { config ->
        val states = blocks.associate { it.start to agendaState(it, noteTitle, config, source) }
        val requests = states.values.mapNotNull { it.request }.distinct()
        if (requests.isEmpty()) {
            flowOf(states)
        } else {
            // Shown at once, before the first snapshot.
            combine(
                requests.map { r ->
                    source.events(r).map<AgendaSnapshot, AgendaSnapshot?> { it }.onStart { emit(null) }
                },
            ) { snapshots ->
                val byRequest = requests.zip(snapshots.toList()).toMap()
                states.mapValues { (_, s) -> s.copy(snapshot = s.request?.let(byRequest::get)) }
            }
        }
    }
}
