# The Date locale is detected from the existing journal

A Daily note's Template writes month and weekday names (`{{date:MMMM}}` gives `octobre` in this vault). Obsidian takes them from its own interface language, which it keeps in the desktop app's local storage, not in the vault, so the phone can't read it. Using the phone locale would write `October` on an English phone into a journal written in French. So citti reads the first line of up to 10 recent Daily notes, spread over different months, and counts a note for a language when that line contains, as a whole word, the month name of the note's own date in that language. Three agreeing notes decide; the result is stored and only reset when the Journal folder changes. A manual choice in Settings overrides it. The cost: a journal whose Template writes no month name never decides, and falls back to the phone locale.

## Considered options

- **Phone locale:** no logic, but wrong whenever the phone and the vault differ.
- **Ask on first use:** always right, but one more question before the first Daily note, and most users won't know what it means.
- **Read Obsidian's language:** not stored in the vault.
