# Search uses SQLite FTS4, kept in sync by triggers

Search must cover the title, text and folder path of every synced note (about 2.2k, Hidden folders included) offline and as you type, and the vault is largely French, so `été` must match `ete` and `Été`. SQLite's `LIKE` and `lower()` fold ASCII only and scan every row on each keystroke. FTS5 would add a relevance score, but Android's framework SQLite does not guarantee it (the test phone, SQLite 3.50.6, has no `fts5` module), while the xerial JDBC driver our tests run on does, so it could pass in CI and fail on the phone. So search uses an external-content FTS4 table over `note(title, content, category)` with the `unicode61` tokenizer and `remove_diacritics=1`, and SQLite triggers on `note` keep it in sync, so no write path (sync, editor, sign-out wipe) can forget to update it. Each typed word is a prefix query; ranking is by tier (title, then text, then path) and recency, computed from `matchinfo`, not by relevance. SQLDelight does not know `matchinfo()`, so that one query runs through the driver directly with the same SQL; the table, the triggers and the snippet query stay in `.sq` and `.sqm` files. The cost is that a match in the middle of a word (`tag` in `etag`) is not found.

## Considered options

- **`LIKE` / `instr` scan:** finds any substring, but ASCII-only case folding, no accent folding, and a full scan of every note's text per keystroke.
- **Bundled SQLite with FTS5:** bm25 ranking and the same engine everywhere, but a bigger APK, a driver change, and a native library to keep updated.
- **FTS4 updated from Kotlin:** same index, but every write path must remember to update it; triggers make that impossible to miss.
