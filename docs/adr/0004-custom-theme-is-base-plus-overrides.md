# The custom theme is a base preset plus sparse overrides, a Theme file

The user gets one custom theme. It stores a base preset (Signal, Paper or Index) and only the values the user changed (colors per light/dark, font roles, density, shape, label style). Everything else is resolved from the base at runtime, so fixes and improvements to a preset reach the custom theme too, and the saved file stays small and human-readable. In 1.0.0 the selection and the custom theme are stored on the device (`setting` table) and moved between devices by exporting and importing the same JSON; syncing it as `.citti/appearance.json` at the vault root over WebDAV, last write wins, is planned for 1.1.0.

## Considered options

- **Full copy of all tokens:** simple to load, but freezes the custom theme at the preset's state when it was made and makes the JSON noisy.
- **Several custom themes:** more flexible, more UI (list, naming, deleting) for a need not felt yet. One slot can grow into many later because the file format is `schema`-versioned.
- **Store in the Notes API settings:** `GET/PUT /settings` only knows `notesPath` and `fileSuffix`, so it cannot hold this.
- **Sync in 1.0.0:** dropped on 2026-10-31 to keep M8 free of a second sync path and its conflict rules; the file format already allows it later.
