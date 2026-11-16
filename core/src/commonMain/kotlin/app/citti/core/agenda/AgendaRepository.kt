package app.citti.core.agenda

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.citti.core.api.ApiError
import app.citti.core.api.ApiResult
import app.citti.core.dav.CalDavClient
import app.citti.core.db.CittiDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** What one Agenda block asks for: the [calendars] (hrefs) over [days] days from [firstDay] in [zone]. */
data class AgendaRequest(
    val calendars: List<String>,
    val firstDay: LocalDate,
    val days: Int,
    val zone: String,
)

/** What is known of one Event calendar for one request. */
data class CalendarEvents(
    /** The cached events, or null when none were ever fetched for this range. */
    val events: List<CalEvent>?,
    val fetchedAt: Instant?,
    /** The last refresh failed with this error; the cached events (if any) are still the ones shown. */
    val error: ApiError?,
)

data class AgendaSnapshot(
    val calendars: Map<String, CalendarEvents>,
    /** A refresh of this request is running. */
    val refreshing: Boolean,
)

/**
 * The events of the Agenda blocks of viewed Notes (ADR 0012). [observe] shows what `agenda_cache` holds for one
 * request at once, and refreshes it in the background when it is missing or older than [maxAge]. Nothing else
 * makes a request: no prefetch, no Sync. Rows not viewed for [keepUnviewed] go, and at most [maxRows] stay.
 */
class AgendaRepository(
    private val db: CittiDatabase,
    private val calDav: () -> CalDavClient?,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val io: CoroutineDispatcher = Dispatchers.Default,
    private val maxAge: Duration = 15.minutes,
    private val keepUnviewed: Duration = 30.days,
    private val maxRows: Long = 200,
) {
    private val queries = db.agendaQueries
    private val json = Json { ignoreUnknownKeys = true }

    /** The cache key of one calendar's row. */
    private data class Key(
        val calendar: String,
        val firstDay: LocalDate,
        val days: Int,
        val zone: String,
    )

    private data class Status(
        val running: Set<AgendaRequest> = emptySet(),
        val errors: Map<Key, ApiError> = emptyMap(),
    )

    private val status = MutableStateFlow(Status())
    private val jobs = MutableStateFlow<Map<AgendaRequest, Job>>(emptyMap())

    /**
     * The cached events of [request], updated as they change. Starting to collect marks the rows viewed and
     * refreshes the stale ones.
     */
    fun observe(request: AgendaRequest): Flow<AgendaSnapshot> =
        combine(
            queries
                .selectRange(request.firstDay.toString(), request.days.toLong(), request.zone, request.calendars)
                .asFlow()
                .mapToList(io),
            status,
        ) { rows, status ->
            val byCalendar = rows.associateBy { it.calendar }
            AgendaSnapshot(
                calendars =
                    request.calendars.associateWith { href ->
                        val row = byCalendar[href]
                        CalendarEvents(
                            events = row?.let { decode(it.events) },
                            fetchedAt = row?.let { Instant.fromEpochMilliseconds(it.fetched_at) },
                            error = status.errors[key(href, request)],
                        )
                    },
                refreshing = request in status.running,
            )
        }.distinctUntilChanged()
            .onStart { scope.launch { opened(request) } }

    /** Fetches [request] again now (the Refresh action), unless that is already running. */
    fun refresh(request: AgendaRequest) {
        start(request, force = true)
    }

    /** Drops the cached events of [calendars] (disabled or gone). */
    fun forget(calendars: Set<String>) {
        if (calendars.isNotEmpty()) queries.deleteCalendars(calendars)
    }

    private suspend fun opened(request: AgendaRequest) {
        withContext(io) {
            queries.touchRange(
                clock.now().toEpochMilliseconds(),
                request.firstDay.toString(),
                request.days.toLong(),
                request.zone,
                request.calendars,
            )
        }
        start(request, force = false)
    }

    private fun start(
        request: AgendaRequest,
        force: Boolean,
    ) {
        // A lazy job, started only if no other is running for the same request: swiping between Notes or toggling
        // the mode never sends the same requests twice.
        lateinit var job: Job
        job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    fetch(request, force)
                } finally {
                    jobs.update { if (it[request] === job) it - request else it }
                }
            }
        while (true) {
            val current = jobs.value
            if (current[request]?.isActive == true) {
                job.cancel()
                return
            }
            if (jobs.compareAndSet(current, current + (request to job))) {
                job.start()
                return
            }
        }
    }

    private suspend fun fetch(
        request: AgendaRequest,
        force: Boolean,
    ) {
        val client = calDav()
        val now = clock.now()
        val stale =
            withContext(io) {
                val fresh =
                    queries
                        .selectRange(request.firstDay.toString(), request.days.toLong(), request.zone, request.calendars)
                        .executeAsList()
                        .filter { now - Instant.fromEpochMilliseconds(it.fetched_at) < maxAge }
                        .map { it.calendar }
                        .toSet()
                if (force) request.calendars else request.calendars.filter { it !in fresh }
            }
        if (stale.isEmpty()) return
        if (client == null) {
            setErrors(request, stale.associateWith { ApiError.DavNotReady })
            return
        }
        status.update { it.copy(running = it.running + request) }
        try {
            val zone = TimeZone.of(request.zone)
            val from = request.firstDay.atStartOfDayIn(zone)
            val to = request.firstDay.plus(DatePeriod(days = request.days)).atStartOfDayIn(zone)
            val permits = Semaphore(PARALLEL)
            val results =
                withContext(io) {
                    stale.map { href -> async { href to permits.withPermit { client.events(href, from, to) } } }.awaitAll()
                }
            val errors = mutableMapOf<String, ApiError>()
            withContext(io) {
                val at = clock.now().toEpochMilliseconds()
                db.transaction {
                    for ((href, result) in results) {
                        when (result) {
                            is ApiResult.Success -> {
                                queries.upsertRange(
                                    href,
                                    request.firstDay.toString(),
                                    request.days.toLong(),
                                    request.zone,
                                    json.encodeToString(result.value),
                                    at,
                                    at,
                                )
                            }

                            is ApiResult.Failure -> {
                                errors[href] = result.error
                            }
                        }
                    }
                    queries.pruneUnviewed(at - keepUnviewed.inWholeMilliseconds)
                    queries.pruneToCount(maxRows)
                }
            }
            setErrors(request, stale.associateWith { errors[it] })
        } finally {
            status.update { it.copy(running = it.running - request) }
        }
    }

    private fun setErrors(
        request: AgendaRequest,
        errors: Map<String, ApiError?>,
    ) = status.update { s ->
        val next = s.errors.toMutableMap()
        errors.forEach { (href, error) -> if (error == null) next.remove(key(href, request)) else next[key(href, request)] = error }
        s.copy(errors = next)
    }

    private fun key(
        href: String,
        request: AgendaRequest,
    ) = Key(href, request.firstDay, request.days, request.zone)

    private fun decode(text: String): List<CalEvent> = runCatching { json.decodeFromString<List<CalEvent>>(text) }.getOrDefault(emptyList())

    private companion object {
        const val PARALLEL = 4
    }
}
