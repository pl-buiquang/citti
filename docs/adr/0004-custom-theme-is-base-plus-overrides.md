# The custom theme is a base preset plus sparse overrides, synced as a vault file

The user gets one custom theme. It stores a base preset (Signal, Paper or Index) and only the values the user changed (colors per light/dark, font roles, density, shape, label style). Everything else is resolved from the base at runtime, so fixes and improvements to a preset reach the custom theme too, and the saved file stays small and human-readable. The selected theme, mode, accent source and custom theme live in `.citti/appearance.json` at the vault root over WebDAV, so the look follows the user across devices without any server-side support; on conflict the last write wins.

## Considered options

- **Full copy of all tokens:** simple to load, but freezes the custom theme at the preset's state when it was made and makes the JSON noisy.
- **Several custom themes:** more flexible, more UI (list, naming, deleting) for a need not felt yet. One slot can grow into many later because the file format is `schema`-versioned.
- **Store in the Notes API settings:** `GET/PUT /settings` only knows `notesPath` and `fileSuffix`, so it cannot hold this.
