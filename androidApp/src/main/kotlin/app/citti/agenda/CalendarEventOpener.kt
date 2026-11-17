package app.citti.agenda

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Intent
import android.provider.CalendarContract
import app.citti.core.agenda.AgendaOccurrence
import kotlin.math.abs
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** One instance the calendar provider has around an occurrence's time. */
data class ProviderInstance(
    val eventId: Long,
    /** Epoch milliseconds. */
    val begin: Long,
    val end: Long,
    val uid: String?,
    val title: String?,
    val allDay: Boolean,
)

/**
 * Finds an Agenda item's event in the phone's calendar provider (synced there by DAVx⁵ or another CalDAV app) and
 * builds the intent that opens it in the default calendar app, such as Business Calendar (ADR 0013). Without the
 * READ_CALENDAR permission, or when the event is not on the phone, the intent opens the calendar at its time.
 */
object CalendarEventOpener {
    /** The instances within a day of [occurrence], with their event's UID. Needs READ_CALENDAR. */
    fun instancesAround(
        resolver: ContentResolver,
        occurrence: AgendaOccurrence,
    ): List<ProviderInstance> {
        val from = occurrence.start - 1.days
        val to = maxOf(occurrence.end, occurrence.start) + 1.days
        val uri =
            CalendarContract.Instances.CONTENT_URI
                .buildUpon()
                .let { ContentUris.appendId(it, from.toEpochMilliseconds()) }
                .let { ContentUris.appendId(it, to.toEpochMilliseconds()) }
                .build()
        val projection =
            arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.ALL_DAY,
            )
        val instances =
            resolver.query(uri, projection, null, null, null)?.use { c ->
                buildList<ProviderInstance> {
                    while (c.moveToNext()) {
                        add(
                            ProviderInstance(
                                c.getLong(0),
                                c.getLong(1),
                                c.getLong(2),
                                uid = null,
                                title = c.getString(3),
                                allDay =
                                    c.getInt(4) == 1,
                            ),
                        )
                    }
                }
            } ?: return emptyList()
        if (instances.isEmpty()) return instances
        val ids = instances.map { it.eventId }.distinct()
        val uids = HashMap<Long, String?>()
        resolver
            .query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID, CalendarContract.Events.UID_2445),
                "${CalendarContract.Events._ID} IN (${ids.joinToString(",") { "?" }})",
                ids.map { it.toString() }.toTypedArray(),
                null,
            )?.use { c -> while (c.moveToNext()) uids[c.getLong(0)] = c.getString(1) }
        return instances.map { it.copy(uid = uids[it.eventId]) }
    }

    /**
     * The instance that is [occurrence]: the same UID starting at the same time; else the same UID nearest in time
     * (a time zone or an edited occurrence); else, for an event without a UID in the provider, the same title at
     * the same time. All-day instances are stored at UTC midnight, so the day is compared, not the instant.
     */
    fun pick(
        candidates: List<ProviderInstance>,
        occurrence: AgendaOccurrence,
    ): ProviderInstance? {
        val start = occurrence.start.toEpochMilliseconds()
        val tolerance = if (occurrence.allDay) 14 * 60 * 60_000L else 1.minutes.inWholeMilliseconds

        fun distance(i: ProviderInstance) = abs(i.begin - start)
        val sameUid = candidates.filter { occurrence.uid.isNotEmpty() && it.uid == occurrence.uid }
        sameUid.filter { distance(it) <= tolerance }.minByOrNull(::distance)?.let { return it }
        sameUid.minByOrNull(::distance)?.let { return it }
        return candidates
            .filter { it.uid.isNullOrEmpty() && it.title == occurrence.summary && distance(it) <= tolerance }
            .minByOrNull(::distance)
    }

    /** Opens [instance]'s event at that occurrence. */
    fun eventIntent(instance: ProviderInstance): Intent =
        Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, instance.eventId))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, instance.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, instance.end)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, instance.allDay)

    /** Opens the calendar app at [occurrence]'s time. */
    fun timeIntent(occurrence: AgendaOccurrence): Intent =
        Intent(
            Intent.ACTION_VIEW,
            CalendarContract.CONTENT_URI
                .buildUpon()
                .appendPath("time")
                .let { ContentUris.appendId(it, occurrence.start.toEpochMilliseconds()) }
                .build(),
        )
}
