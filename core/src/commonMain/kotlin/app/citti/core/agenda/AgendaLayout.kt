package app.citti.core.agenda

import app.citti.core.daily.DateLocale
import app.citti.core.daily.MomentFormat
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Instant

/** Settings › Agenda, the defaults of every Agenda block. */
data class AgendaDisplay(
    val template: String = DEFAULT_TEMPLATE,
    val timeFormat: String = "HH:mm",
    val dayFormat: String = "dddd D MMMM",
    val days: Int = 1,
    val past: PastMode = PastMode.DIM,
    val showAllDay: Boolean = true,
    val hideCancelled: Boolean = true,
) {
    companion object {
        const val DEFAULT_TEMPLATE = "{{when}} · **{{summary}}**{{#location}} — {{location}}{{/location}}"
    }
}

/** An event with the Event calendar it came from, as named and colored in Settings › Agenda. */
data class CalendarEvent(
    val event: CalEvent,
    val calendar: String,
    /** `#rrggbb`, or empty. */
    val color: String,
)

/** What an Agenda block shows, ready to draw: the Markdown of each event, grouped by day. */
data class AgendaView(
    val days: List<AgendaDayView>,
    /** The text shown when nothing at all is listed (only when there are no per-day lines). */
    val empty: String?,
)

data class AgendaDayView(
    val date: LocalDate,
    /** The day header, or null when the block does not group by day. */
    val header: String?,
    val items: List<AgendaItemView>,
    /** The empty-day line under the header. */
    val empty: String?,
)

/**
 * One occurrence of an event, as an Agenda item shows it: what a calendar app needs to find it again (the
 * iCalendar [uid] and the occurrence's times; a recurring event has one per day).
 */
data class AgendaOccurrence(
    val uid: String,
    val summary: String,
    val start: Instant,
    val end: Instant,
    val allDay: Boolean,
)

data class AgendaItemView(
    val markdown: String,
    /** Ended already, and the block dims past events. */
    val dimmed: Boolean,
    val color: String,
    val allDay: Boolean,
    val occurrence: AgendaOccurrence,
)

/** The texts the layout needs that come from the UI's strings. */
data class AgendaTexts(
    val allDay: String,
    val noTitle: String,
    val empty: String,
)

object AgendaLayout {
    /**
     * Lays out [events] over [days] days from [start] in [zone], as the plugin does: all-day events first, then by
     * start time and summary; an event crossing midnight shows on each of its days with an open end (`22:00–…`).
     */
    fun layout(
        events: List<CalendarEvent>,
        options: AgendaOptions,
        display: AgendaDisplay,
        start: LocalDate,
        now: Instant,
        zone: TimeZone,
        locale: DateLocale,
        texts: AgendaTexts,
    ): AgendaView {
        val days = options.days ?: display.days
        val showAllDay = options.allDay ?: display.showAllDay
        val past = options.past ?: display.past
        val group = options.group ?: (days > 1)
        val template = options.template ?: display.template
        val emptyText = options.empty ?: texts.empty
        val perDay = group && days <= 7
        val visible =
            events.filter { (showAllDay || !it.event.allDay) && !(display.hideCancelled && it.event.status == "CANCELLED") }

        fun time(i: Instant): String {
            val local = i.toLocalDateTime(zone)
            return MomentFormat.format(local.date, local.time, display.timeFormat, locale)
        }

        fun day(d: LocalDate) = MomentFormat.format(d, LocalTime(0, 0), display.dayFormat, locale)

        val out =
            (0 until days).map { i ->
                val date = start.plus(DatePeriod(days = i))
                val dayStart = date.atStartOfDayIn(zone)
                val dayEnd = date.plus(DatePeriod(days = 1)).atStartOfDayIn(zone)
                val items =
                    visible
                        .map { it to Span(it.event.start.instantIn(zone), it.event.end.instantIn(zone)) }
                        .filter { (_, span) -> span.overlaps(dayStart, dayEnd) }
                        .sortedWith(
                            compareBy<Pair<CalendarEvent, Span>> { !it.first.event.allDay }
                                .thenBy { it.second.start }
                                .thenBy { it.first.event.summary },
                        ).mapNotNull { (ce, span) ->
                            val ended = maxOf(span.end, span.start) <= now
                            if (ended && past == PastMode.HIDE) return@mapNotNull null
                            val values = values(ce, span, dayStart, dayEnd, texts, ::time, ::day, zone)
                            AgendaItemView(
                                markdown = AgendaTemplate.render(template, values),
                                dimmed = ended && past == PastMode.DIM,
                                color = ce.color,
                                allDay = ce.event.allDay,
                                occurrence = AgendaOccurrence(ce.event.uid, ce.event.summary, span.start, span.end, ce.event.allDay),
                            )
                        }
                AgendaDayView(
                    date = date,
                    header = if (group && (items.isNotEmpty() || days <= 7)) day(date) else null,
                    items = items,
                    empty = if (items.isEmpty() && perDay) emptyText else null,
                )
            }
        val shown = out.sumOf { it.items.size }
        return AgendaView(out, if (shown == 0 && !perDay) emptyText else null)
    }

    private data class Span(
        val start: Instant,
        val end: Instant,
    ) {
        /** Zero-length events count when they start inside the range. */
        fun overlaps(
            from: Instant,
            to: Instant,
        ): Boolean {
            val e = maxOf(end, start)
            return if (e == start) start >= from && start < to else start < to && e > from
        }
    }

    private fun values(
        ce: CalendarEvent,
        span: Span,
        dayStart: Instant,
        dayEnd: Instant,
        texts: AgendaTexts,
        time: (Instant) -> String,
        day: (LocalDate) -> String,
        zone: TimeZone,
    ): Map<String, String> {
        val e = ce.event
        val `when` =
            if (e.allDay) {
                texts.allDay
            } else {
                val from = if (span.start < dayStart) "…" else time(span.start)
                val to = if (span.end > dayEnd) "…" else time(span.end)
                if (span.end <= span.start) from else "$from–$to"
            }
        return mapOf(
            "when" to `when`,
            "start" to if (e.allDay) "" else time(span.start),
            "end" to if (e.allDay) "" else time(span.end),
            "day" to day(span.start.toLocalDateTime(zone).date),
            "duration" to if (e.allDay) "" else formatDuration(span.end - span.start),
            "summary" to e.summary.ifEmpty { texts.noTitle },
            "location" to e.location,
            "description" to e.description,
            "calendar" to ce.calendar,
            "url" to e.url,
            "status" to e.status.lowercase(),
        )
    }

    /** `1h 30min`, `1d 2h`, or empty for zero and negative durations. */
    fun formatDuration(d: Duration): String {
        val total = d.inWholeSeconds.let { (it + 30) / 60 }
        if (total <= 0) return ""
        val days = total / 1440
        val h = (total % 1440) / 60
        val m = total % 60
        return listOfNotNull(
            days.takeIf { it > 0 }?.let { "${it}d" },
            h.takeIf { it > 0 }?.let { "${it}h" },
            m.takeIf { it > 0 }?.let { "${it}min" },
        ).joinToString(" ")
    }
}
