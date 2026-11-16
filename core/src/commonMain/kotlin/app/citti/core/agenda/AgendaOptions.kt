package app.citti.core.agenda

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** What an Agenda block does with events that already ended. */
enum class PastMode { SHOW, DIM, HIDE }

/**
 * The options of an Agenda block, one `key: value` per line, as in the Obsidian CalDAV Agenda plugin. A null field
 * falls back to Settings › Agenda.
 */
data class AgendaOptions(
    /** `YYYY-MM-DD`, `today`, `tomorrow`, `yesterday` or a day offset like `+2` / `-1`. */
    val date: String? = null,
    val days: Int? = null,
    /** Event calendar names to include (case-insensitive); null means every enabled one. */
    val calendars: List<String>? = null,
    val template: String? = null,
    val past: PastMode? = null,
    val empty: String? = null,
    /** A header per day; null means "when there is more than one day". */
    val group: Boolean? = null,
    val allDay: Boolean? = null,
)

/** A line of an Agenda block that was ignored. The UI turns it into a message. */
sealed interface OptionError {
    data class NoColon(
        val line: String,
    ) : OptionError

    data class BadDays(
        val value: String,
    ) : OptionError

    data class BadPast(
        val value: String,
    ) : OptionError

    data class UnknownKey(
        val key: String,
    ) : OptionError
}

data class ParsedOptions(
    val options: AgendaOptions,
    val errors: List<OptionError>,
)

const val MAX_AGENDA_DAYS = 366

private val TRUE = Regex("^(true|yes|on|1)$", RegexOption.IGNORE_CASE)

/** Parses the text of an Agenda block. Blank lines and lines starting with `#` or `//` are comments. */
fun parseAgendaOptions(source: String): ParsedOptions {
    var options = AgendaOptions()
    val errors = mutableListOf<OptionError>()
    for (raw in source.lines()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
        val colon = line.indexOf(':')
        if (colon < 0) {
            errors += OptionError.NoColon(line)
            continue
        }
        val key = line.substring(0, colon).trim().lowercase()
        val value = line.substring(colon + 1).trim()
        options =
            when (key) {
                "date" -> {
                    options.copy(date = value)
                }

                "days" -> {
                    val n = value.toIntOrNull()
                    if (n != null && n in 1..MAX_AGENDA_DAYS) {
                        options.copy(days = n)
                    } else {
                        errors += OptionError.BadDays(value)
                        options
                    }
                }

                "calendars", "calendar" -> {
                    options.copy(calendars = value.split(',').map { it.trim() }.filter { it.isNotEmpty() })
                }

                "template" -> {
                    options.copy(template = value)
                }

                "past" -> {
                    when (value) {
                        "show" -> {
                            options.copy(past = PastMode.SHOW)
                        }

                        "dim" -> {
                            options.copy(past = PastMode.DIM)
                        }

                        "hide" -> {
                            options.copy(past = PastMode.HIDE)
                        }

                        else -> {
                            errors += OptionError.BadPast(value)
                            options
                        }
                    }
                }

                "empty" -> {
                    options.copy(empty = value)
                }

                "group" -> {
                    options.copy(group = TRUE.matches(value))
                }

                "allday" -> {
                    options.copy(allDay = TRUE.matches(value))
                }

                else -> {
                    errors += OptionError.UnknownKey(key)
                    options
                }
            }
    }
    return ParsedOptions(options, errors)
}

private val YMD = Regex("""^(\d{4})-(\d{2})-(\d{2})""")
private val OFFSET = Regex("""^[+-]\d+$""")

/**
 * The first day of an Agenda block. Without an explicit [value], a Note whose title starts with `YYYY-MM-DD` (a
 * Daily note in the plugin's sense) uses that date, any other Note uses [today]. Null when [value] is not a date.
 */
fun resolveStartDate(
    value: String?,
    noteTitle: String,
    today: LocalDate,
): LocalDate? {
    if (value.isNullOrBlank()) return ymd(noteTitle) ?: today
    val v = value.trim().lowercase()
    return when {
        v == "today" -> today
        v == "tomorrow" -> today.plus(DatePeriod(days = 1))
        v == "yesterday" -> today.plus(DatePeriod(days = -1))
        OFFSET.matches(v) -> v.removePrefix("+").toIntOrNull()?.let { today.plus(DatePeriod(days = it)) }
        else -> ymd(v)
    }
}

private fun ymd(text: String): LocalDate? {
    val m = YMD.find(text) ?: return null
    return try {
        LocalDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    } catch (_: IllegalArgumentException) {
        null
    }
}
