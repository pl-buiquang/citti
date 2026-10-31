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

`GLOSSARY.md` and `docs/adr/` are in this repo and are the source; the vault only keeps a pointer.

## Rules

- **Tokens only**: components read semantic theme tokens and theme roles, never raw colors or dp values. A theme is a preset plus overrides (ADR 0004).
- **Offline-first**: the local DB is the source for the UI and the widgets; edits made offline are queued.
- **Never rewrite the user's markdown**: the editor and the renderer leave the text as typed.
- **Case-insensitive headers**: ETag and Last-Modified lookups must not depend on header case.
- **Exclusions are client-side**: Unsynced folders are never downloaded or searched; Hidden folders are synced and searchable but not listed.
- **Hidden is filtered inline** in each List, Tree and Search query (never in a shared helper): the Calendar (M6) must see Hidden notes.
- **Conflicts**: server wins, local text becomes a conflict copy (ADR 0003).
- **Performance budgets**: no-change sync is a single 304; the real 2.2k-note vault syncs in reasonable time; long notes (thousands of lines) scroll and type without lag (see roadmap exit criteria).
- **The editor saves through `NoteEditor.save`** with the etag its text was based on (edit base); never write a Note's `content` from the UI any other way.
- **Images and other Vault files go through `DavClient`**; never build a WebDAV URL by hand.
- **Search goes through `note_fts`** (ADR 0007): never query `content` with LIKE.
- **Every HTTP client is built from `AppConfig.httpEngine`**, so the E2E fake sees every request (a request it does not route fails the test).
- **Dates go through kotlinx-datetime and `MomentFormat`**; never format a Daily note title or a Template date any other way.
- **The Journal folder can never be an Unsynced folder** (`ExclusionRepository.add`, `DailySettings.setFolder`).

## Commands

- Build: `./gradlew assembleDebug` (APK in `androidApp/build/outputs/apk/debug/`)
- Test and lint: `./gradlew spotlessCheck check`; fix formatting with `./gradlew spotlessApply`
- JVM tests only: `./gradlew :core:jvmTest`
- E2E tests: `./gradlew e2e` (managed emulator, AOSP ATD API 36, against `FakeNotesServer` and the Fixture Vault in `core-testing`; not part of `check`). One flow: `./gradlew e2e -Pe2e.class=app.citti.e2e.flows.SearchTest`. CI: the `e2e` job of `ci.yml`, run by hand (`workflow_dispatch`).
- Release build: `./gradlew assembleRelease`. It is signed when `keystore.properties` or the `CITTI_*` env vars are set (see `keystore.properties.example`), unsigned otherwise. Tagging `X.Y.Z` publishes a signed GitHub release (`.github/workflows/release.yml`).
- Install on a device or emulator: `adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk`
- Needs JDK 17 and the Android SDK (`sdk.dir` in `local.properties`, which is gitignored)
