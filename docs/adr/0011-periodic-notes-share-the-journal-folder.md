# Periodic notes share the Journal folder and are found by generating their titles

The Week, Month, Quarter and Year notes of a vault written with Obsidian sit next to the Daily notes (`2026-W44`, `2026-octobre`, `2026-Q4`, `2026` in `journal/`). citti puts them in the Journal folder too, with one title format per kind (defaults `GGGG-[W]WW`, `YYYY-MMMM`, `YYYY-[Q]Q`, `YYYY`) and an optional Template per kind. No folder per kind: the guard that keeps the Journal folder synced, the Folder refresh before creating, and the Hidden and Unsynced rules then stay as they are for all five kinds.

A Daily note's title is read back to a date with `MomentFormat.parse`. That cannot work for the others: `MMMM`, `Q` and `WW` don't say which year, and the week of 2027-01-01 is `2026-W53`. So a title is matched the other way round: the years written in it are the candidates, every period of those years is formatted with the kind's format, and the period whose title is exactly the title is the note. A format is refused when neighbouring periods would get the same title, or when it writes another kind's title for the same date.

The cost: a title is only recognised when it has a year in it, and a lookup formats about 70 periods per candidate year and kind (the index does it once per title that isn't a Daily note, off the main thread).

## Considered options

- **A folder per kind (Periodic Notes plugin):** four more folders to guard and refresh, for a vault that keeps them together.
- **Parse the title with a regular expression per token:** works for `YYYY-[W]WW`, not for `GGGG` or `MMMM` without a year, and it would have to follow every format a user can write.
- **Keep a table of period to note, filled when a note is created:** misses the notes made in Obsidian, which are most of them.
