package app.citti.core.settings

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.citti.core.agenda.AgendaDisplay
import app.citti.core.agenda.MAX_AGENDA_DAYS
import app.citti.core.agenda.PastMode
import app.citti.core.dav.DiscoveredCalendar
import app.citti.core.db.CittiDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Prefix of every Agenda `setting` key; they are device-local and wiped at sign-out. */
const val AGENDA_PREFIX = "agenda."

/** `setting` key of the Event calendars, a JSON list of [EventCalendar]. */
const val KEY_AGENDA_CALENDARS = "agenda.calendars"
const val KEY_AGENDA_TEMPLATE = "agenda.template"
const val KEY_AGENDA_TIME_FORMAT = "agenda.time-format"
const val KEY_AGENDA_DAY_FORMAT = "agenda.day-format"
const val KEY_AGENDA_DAYS = "agenda.days"
const val KEY_AGENDA_PAST = "agenda.past"

/** `setting` key set to `0` when Agenda blocks leave out all-day events. */
const val KEY_AGENDA_ALL_DAY = "agenda.allday"

/** `setting` key set to `0` when Agenda blocks show cancelled events. */
const val KEY_AGENDA_HIDE_CANCELLED = "agenda.hide-cancelled"

/** An Event calendar: what the server says, and the user's name and color over it. */
@Serializable
data class EventCalendar(
    /** The calendar's path on the Account's server, from [DiscoveredCalendar.href]. */
    val href: String,
    val serverName: String,
    val serverColor: String = "",
    val enabled: Boolean = true,
    /** The user's name, or null for [serverName]. */
    val rename: String? = null,
    /** The user's color (`#rrggbb`), or null for [serverColor]. */
    val recolor: String? = null,
) {
    val name: String get() = rename ?: serverName
    val color: String get() = recolor ?: serverColor

    /** True when an Agenda block's `calendars:` entry [wanted] means this calendar (its name or the server's). */
    fun matches(wanted: String): Boolean = name.equals(wanted, ignoreCase = true) || serverName.equals(wanted, ignoreCase = true)
}

/** A snapshot of Settings › Agenda. */
data class AgendaConfig(
    val calendars: List<EventCalendar> = emptyList(),
    val display: AgendaDisplay = AgendaDisplay(),
) {
    val enabled: List<EventCalendar> get() = calendars.filter { it.enabled }
}

private val HEX_COLOR = Regex("^#[0-9a-fA-F]{6}$")

/**
 * Reads and writes Settings › Agenda (`agenda.*` keys of the `setting` table). [onCalendarsDropped] gets the hrefs
 * that stopped being enabled, so their cached events can go.
 */
class AgendaSettings(
    db: CittiDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onCalendarsDropped: (Set<String>) -> Unit = {},
) {
    private val queries = db.settingQueries
    private val json = Json { ignoreUnknownKeys = true }

    fun config(): AgendaConfig = parse(queries.selectSettingsWithPrefix(AGENDA_PREFIX).executeAsList().associate { it.key to it.value_ })

    fun configFlow(): Flow<AgendaConfig> =
        queries
            .selectSettingsWithPrefix(AGENDA_PREFIX)
            .asFlow()
            .mapToList(dispatcher)
            .map { rows -> parse(rows.associate { it.key to it.value_ }) }
            .distinctUntilChanged()

    /**
     * Replaces the list with what the server has now: a calendar already known keeps its enabled flag, name and
     * color; a new one starts enabled; one the server no longer lists is dropped.
     */
    fun mergeDiscovered(discovered: List<DiscoveredCalendar>) {
        val known = config().calendars.associateBy { it.href }
        setCalendars(
            discovered.map { d ->
                known[d.href]?.copy(serverName = d.name, serverColor = d.color) ?: EventCalendar(d.href, d.name, d.color)
            },
        )
    }

    fun setEnabled(
        href: String,
        enabled: Boolean,
    ) {
        update(href) { it.copy(enabled = enabled) }
    }

    /** A blank [name] goes back to the server's name. */
    fun rename(
        href: String,
        name: String,
    ) = update(href) { it.copy(rename = name.trim().takeIf { n -> n.isNotEmpty() && n != it.serverName }) }

    /** Null goes back to the server's color; anything but `#rrggbb` is ignored. */
    fun recolor(
        href: String,
        color: String?,
    ) {
        if (color != null && !HEX_COLOR.matches(color)) return
        update(href) { it.copy(recolor = color) }
    }

    /** A blank template goes back to the default. */
    fun setTemplate(template: String) =
        setOrClear(
            KEY_AGENDA_TEMPLATE,
            template.trim().takeIf {
                it.isNotEmpty() &&
                    it != AgendaDisplay.DEFAULT_TEMPLATE
            },
        )

    /** A blank format goes back to the default. */
    fun setTimeFormat(format: String) =
        setOrClear(
            KEY_AGENDA_TIME_FORMAT,
            format.trim().takeIf {
                it.isNotEmpty() &&
                    it != AgendaDisplay().timeFormat
            },
        )

    /** A blank format goes back to the default. */
    fun setDayFormat(format: String) =
        setOrClear(
            KEY_AGENDA_DAY_FORMAT,
            format.trim().takeIf {
                it.isNotEmpty() &&
                    it != AgendaDisplay().dayFormat
            },
        )

    /** Clamped to 1–366. */
    fun setDays(days: Int) {
        queries.upsertSetting(KEY_AGENDA_DAYS, days.coerceIn(1, MAX_AGENDA_DAYS).toString())
    }

    fun setPast(mode: PastMode) {
        queries.upsertSetting(KEY_AGENDA_PAST, mode.name.lowercase())
    }

    fun setShowAllDay(show: Boolean) {
        queries.upsertSetting(KEY_AGENDA_ALL_DAY, if (show) "1" else "0")
    }

    fun setHideCancelled(hide: Boolean) {
        queries.upsertSetting(KEY_AGENDA_HIDE_CANCELLED, if (hide) "1" else "0")
    }

    private fun update(
        href: String,
        change: (EventCalendar) -> EventCalendar,
    ) = setCalendars(config().calendars.map { if (it.href == href) change(it) else it })

    private fun setCalendars(calendars: List<EventCalendar>) {
        val before = config().enabled.map { it.href }.toSet()
        queries.upsertSetting(KEY_AGENDA_CALENDARS, json.encodeToString(calendars))
        val dropped = before - calendars.filter { it.enabled }.map { it.href }.toSet()
        if (dropped.isNotEmpty()) onCalendarsDropped(dropped)
    }

    private fun setOrClear(
        key: String,
        value: String?,
    ) {
        if (value == null) queries.deleteSetting(key) else queries.upsertSetting(key, value)
    }

    private fun parse(values: Map<String, String>): AgendaConfig {
        val defaults = AgendaDisplay()
        val calendars =
            values[KEY_AGENDA_CALENDARS]
                ?.let { runCatching { json.decodeFromString<List<EventCalendar>>(it) }.getOrNull() }
                .orEmpty()
        return AgendaConfig(
            calendars = calendars,
            display =
                AgendaDisplay(
                    template = values[KEY_AGENDA_TEMPLATE] ?: defaults.template,
                    timeFormat = values[KEY_AGENDA_TIME_FORMAT] ?: defaults.timeFormat,
                    dayFormat = values[KEY_AGENDA_DAY_FORMAT] ?: defaults.dayFormat,
                    days = values[KEY_AGENDA_DAYS]?.toIntOrNull()?.coerceIn(1, MAX_AGENDA_DAYS) ?: defaults.days,
                    past = PastMode.entries.firstOrNull { it.name.equals(values[KEY_AGENDA_PAST], ignoreCase = true) } ?: defaults.past,
                    showAllDay = values[KEY_AGENDA_ALL_DAY]?.let { it != "0" } ?: defaults.showAllDay,
                    hideCancelled = values[KEY_AGENDA_HIDE_CANCELLED]?.let { it != "0" } ?: defaults.hideCancelled,
                ),
        )
    }
}
