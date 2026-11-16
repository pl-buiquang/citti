# Agenda blocks read CalDAV with the Account, and cache only what was viewed

The vault's Daily notes hold ```` ```agenda ```` blocks that the Obsidian CalDAV Agenda plugin renders as the day's events. citti renders the same blocks, with the same options and Event template fields, in Preview mode only.

The events come from the Account's own Nextcloud: the calendar home is `remote.php/dav/calendars/<user-id>/` on the Account's server, and requests use the shared HTTP client, so the app password the user already gave is the only credential. There is no principal or `.well-known` discovery, so another CalDAV server is out of scope. The server expands recurring events (`<c:expand>` in the `calendar-query`), so citti has no RRULE code. Because that client sends Basic auth to every URL, `CalDavClient` refuses any href that is not on the Account's host and under its calendar home.

A multistatus is read by a small hand-written XML reader that refuses DOCTYPE (no external entities, no entity expansion); a multistatus needs a handful of element names, which does not justify a dependency and its R8 rules.

Events are cached in `agenda_cache`, keyed by exactly what one block asked for: calendar, first day, number of days and time zone. A row is only written after a block in a viewed Note asked for it; nothing is prefetched, and none of it is part of Sync, so the no-change Sync stays one 304. A row older than 15 minutes is refreshed in the background while the cached events show. Rows not viewed for 30 days are dropped, and the table is capped at 200 rows. Sign-out wipes it.

The Settings › Agenda list of Event calendars is citti's own (enabled, name, color), not the plugin's `data.json`, which can hold a password.

A calendar's color is the user's data, like an Image's pixels: it is the one raw color the UI draws, as the dot in front of an event.

## Considered options

- **Read the plugin's `data.json`**: no setup, but it can store the password in plain text, and it ties citti to the plugin's internals.
- **Generic CalDAV discovery** (`.well-known`, principal, calendar-home-set): works with any server, but citti only ever signs in to a Nextcloud.
- **xmlutil**: a full XML library for eight element names.
- **Expand RRULEs on the phone**: needs a recurrence engine and VTIMEZONE handling that the server already has.
- **Sync every event**: works offline for any date, but costs requests and storage for days nobody looks at.
