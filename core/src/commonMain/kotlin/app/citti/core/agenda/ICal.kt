package app.citti.core.agenda

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.IllegalTimeZoneException
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The start or end of an event, as the server wrote it. */
@Serializable
sealed interface EventTime {
    /** A `VALUE=DATE`: the same calendar day in every time zone. */
    @Serializable
    @SerialName("day")
    data class Day(
        val date: LocalDate,
    ) : EventTime

    /** A UTC time, or a local time with a known TZID. */
    @Serializable
    @SerialName("at")
    data class At(
        val epochMillis: Long,
    ) : EventTime {
        val instant: Instant get() = Instant.fromEpochMilliseconds(epochMillis)
    }

    /** A local time with no zone (or an unknown TZID): read in the device's zone. */
    @Serializable
    @SerialName("floating")
    data class Floating(
        val local: LocalDateTime,
    ) : EventTime

    /** This time in [zone]; a Day is its midnight. */
    fun instantIn(zone: TimeZone): Instant =
        when (this) {
            is Day -> date.atStartOfDayIn(zone)
            is At -> instant
            is Floating -> local.toInstant(zone)
        }
}

/** One event, or one occurrence of a recurring event (the server expands them). */
@Serializable
data class CalEvent(
    val uid: String,
    val summary: String,
    val location: String = "",
    val description: String = "",
    val url: String = "",
    /** Upper case, e.g. `CONFIRMED`, `CANCELLED`, or empty. */
    val status: String = "",
    val start: EventTime,
    val end: EventTime,
) {
    val allDay: Boolean get() = start is EventTime.Day
}

/**
 * A minimal iCalendar (RFC 5545) reader: the VEVENTs of a `calendar-data`. Recurrence is not expanded here, the
 * calendar-query asks the server to do it. Nested components (VALARM) are skipped.
 */
object ICal {
    fun events(text: String): List<CalEvent> {
        val events = mutableListOf<CalEvent>()
        var props: MutableList<Prop>? = null
        var depth = 0
        for (line in unfold(text)) {
            val p = parseLine(line) ?: continue
            when (p.name) {
                "BEGIN" -> {
                    if (props != null) {
                        depth++
                    } else if (p.value.equals("VEVENT", ignoreCase = true)) {
                        props = mutableListOf()
                    }
                }

                "END" -> {
                    if (props != null && depth > 0) {
                        depth--
                    } else if (props != null && p.value.equals("VEVENT", ignoreCase = true)) {
                        toEvent(props)?.let { events += it }
                        props = null
                    }
                }

                else -> {
                    if (props != null && depth == 0) props += p
                }
            }
        }
        return events
    }

    internal data class Prop(
        val name: String,
        val params: Map<String, String>,
        val value: String,
    )

    internal fun unfold(text: String): List<String> = text.replace(Regex("\r\n|\r"), "\n").replace(Regex("\n[ \t]"), "").split("\n")

    private val PARAM = Regex("""(?:[^;"]|"[^"]*")+""")

    internal fun parseLine(line: String): Prop? {
        // The value starts at the first ':' that is not inside a quoted parameter.
        var inQuotes = false
        var colon = -1
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') {
                inQuotes = !inQuotes
            } else if (c == ':' && !inQuotes) {
                colon = i
                break
            }
        }
        if (colon < 0) return null
        val parts = PARAM.findAll(line.substring(0, colon)).map { it.value }.toMutableList()
        val name = parts.removeFirstOrNull()?.uppercase() ?: return null
        val params =
            parts
                .mapNotNull { p ->
                    val eq = p.indexOf('=')
                    if (eq < 0) null else p.substring(0, eq).uppercase() to p.substring(eq + 1).removeSurrounding("\"")
                }.toMap()
        return Prop(name, params, line.substring(colon + 1))
    }

    internal fun unescape(value: String): String =
        Regex("""\\([\\;,nN])""").replace(value) { m ->
            val c = m.groupValues[1]
            if (c == "n" || c == "N") "\n" else c
        }

    private val DATE = Regex("""^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})(Z)?)?$""")

    internal fun parseTime(prop: Prop): EventTime? {
        val m = DATE.matchEntire(prop.value.trim()) ?: return null
        val g = m.groupValues
        return try {
            val date = LocalDate(g[1].toInt(), g[2].toInt(), g[3].toInt())
            if (g[4].isEmpty() || prop.params["VALUE"].equals("DATE", ignoreCase = true)) return EventTime.Day(date)
            val local = LocalDateTime(date.year, date.month, date.day, g[4].toInt(), g[5].toInt(), g[6].toInt())
            when {
                g[7] == "Z" -> {
                    EventTime.At(local.toInstant(TimeZone.UTC).toEpochMilliseconds())
                }

                else -> {
                    zone(prop.params["TZID"])?.let { EventTime.At(local.toInstant(it).toEpochMilliseconds()) }
                        ?: EventTime.Floating(local)
                }
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** The zone of a TZID: an IANA name, or one behind a vendor prefix like `/mozilla.org/20050126_1/Europe/Paris`. */
    internal fun zone(tzid: String?): TimeZone? {
        if (tzid.isNullOrBlank()) return null
        val candidates =
            listOf(
                tzid.trim(),
                tzid
                    .trim()
                    .split('/')
                    .takeLast(2)
                    .joinToString("/"),
            )
        for (id in candidates.distinct()) {
            try {
                return TimeZone.of(id)
            } catch (_: IllegalTimeZoneException) {
                // Try the next form; an unknown zone (e.g. a Windows name) gives a floating time.
            }
        }
        return null
    }

    private val DURATION = Regex("""^([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$""")

    /** An RFC 5545 DURATION (`P1D`, `PT1H30M`, `-PT15M`); zero when unreadable. */
    internal fun parseDuration(value: String): Duration {
        val m = DURATION.matchEntire(value.trim()) ?: return Duration.ZERO

        fun n(i: Int) = m.groupValues[i].toLongOrNull() ?: 0L
        val d = (n(2) * 7 + n(3)).days + n(4).hours + n(5).minutes + n(6).seconds
        return if (m.groupValues[1] == "-") -d else d
    }

    private fun toEvent(props: List<Prop>): CalEvent? {
        fun get(name: String) = props.firstOrNull { it.name == name }
        val start = get("DTSTART")?.let(::parseTime) ?: return null
        val end =
            get("DTEND")?.let(::parseTime)
                ?: get("DURATION")?.let { plus(start, parseDuration(it.value)) }
                ?: if (start is EventTime.Day) EventTime.Day(start.date.plus(DatePeriod(days = 1))) else start

        fun text(name: String) = unescape(get(name)?.value.orEmpty())
        return CalEvent(
            uid = text("UID"),
            summary = text("SUMMARY"),
            location = text("LOCATION"),
            description = text("DESCRIPTION"),
            url = get("URL")?.value.orEmpty(),
            status = get("STATUS")?.value.orEmpty().uppercase(),
            start = start,
            end = end,
        )
    }

    private fun plus(
        start: EventTime,
        d: Duration,
    ): EventTime =
        when (start) {
            is EventTime.Day -> {
                EventTime.Day(start.date.plus(DatePeriod(days = d.inWholeDays.toInt())))
            }

            is EventTime.At -> {
                EventTime.At(start.epochMillis + d.inWholeMilliseconds)
            }

            is EventTime.Floating -> {
                EventTime.Floating((start.local.toInstant(TimeZone.UTC) + d).toLocalDateTime(TimeZone.UTC))
            }
        }
}
