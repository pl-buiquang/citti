# Agenda items open their event through the phone's calendar provider

A tap on an Agenda item should open that event in the user's calendar app (Business Calendar here). No Android link names a CalDAV event: calendar apps show what the phone's calendar provider holds, which DAVx⁵ (or another CalDAV sync app) fills from the same Nextcloud calendars. So citti asks for READ_CALENDAR on the first tap, looks for the occurrence among the provider's instances within a day of it, and opens `content://com.android.calendar/events/<id>` with the occurrence's begin and end times, so a recurring event opens on the tapped day.

The match is the iCalendar UID (`UID_2445`) at the same start (all-day events by day, the provider storing them at UTC midnight); else the same UID nearest in time (an edited occurrence); else, for a provider event without a UID, the same title at the same time. When the permission is refused, the provider has no match or refuses the query, the item opens the calendar at the occurrence's time (`content://com.android.calendar/time/<millis>`), which needs no permission. citti only reads the provider, and only for the tapped item.

## Considered options

- **Only open the calendar at the time**: no permission, but the user still has to find the event in that day.
- **The event's `URL` property**: rarely set, and it leads to a web page, not the calendar app.
- **An app-specific link for Business Calendar**: undocumented, and it ties citti to one app; the provider intent works with any calendar app.
