# citti

Fast, good-looking note app for Android (iOS later) that syncs with the Nextcloud Notes API and WebDAV. Kotlin Multiplatform + Compose Multiplatform (Ktor, SQLDelight, Glance widgets). Greenfield, not a fork of notes-android, and not affiliated with Nextcloud.

## Before starting work

The spec lives in the vault, `~/Nextcloud/Notes/projects/citti/`:

- `app-description.md`: the spec and its decisions (sync, conflicts, preview, exclusions, Appearance).
- `design-brief.md`: screen inventory and wireframes.
- `design-system/`: themes and design tokens (`tokens.json` is the source; Compose, CSS and DTCG outputs are generated).
- `GLOSSARY.md`: use these terms in code, UI strings and commits (Note, Vault, Folder, Favorite, Unsynced folder, Hidden folder, Custom theme, Mode…).
- `adr/`: read the ADRs before changing anything they cover.
- `roadmap.md` and `plan.md`: milestones and the current step.
- Tickets: the Obsidian tracker rooted there, rules in `~/Nextcloud/Notes/_work/topics/dev_workflow/issue-tracker-obsidian.md`.

GLOSSARY and ADRs move into this repo at M1 (the scaffold); from then on the repo copy is the source.

## Rules

- **Tokens only**: components read semantic theme tokens and theme roles, never raw colors or dp values. A theme is a preset plus overrides (ADR 0004).
- **Offline-first**: the local DB is the source for the UI and the widgets; edits made offline are queued.
- **Never rewrite the user's markdown**: the editor and the renderer leave the text as typed.
- **Case-insensitive headers**: ETag and Last-Modified lookups must not depend on header case.
- **Exclusions are client-side**: Unsynced folders are never downloaded or searched; Hidden folders are synced and searchable but not listed.
- **Conflicts**: server wins, local text becomes a conflict copy (ADR 0003).
- **Performance budgets**: no-change sync is a single 304; the real 2.2k-note vault syncs in reasonable time; long notes (thousands of lines) scroll and type without lag (see roadmap exit criteria).

## Commands

TBD at M1 (scaffold).
