# On conflict the server version wins and the local edit becomes a conflict copy

The app is offline-first, so the same note can change on the phone and on the desktop (Obsidian) before sync. When a push fails with 412 (`If-Match`), the server version stays in place and the local text is saved as a sibling note named `<title> (conflict YYYY-MM-DD)`, with a badge on it. Nothing is ever lost and no merge UI is needed.

## Considered options

- **Last write wins:** simplest, but silently loses one side's edit.
- **Merge screen with diff:** best UX on paper, most work for a rare case.
