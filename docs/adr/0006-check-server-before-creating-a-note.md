# Creating a note checks the server first, and an untouched offline duplicate is dropped

The Notes API never refuses a duplicate title: it silently renames the new note to `<title> (2)`. A Daily note is identified only by its dated file name, so creating one blindly would leave a stray `2026-10-31 (2)` next to the real one, and a 412 never fires because a `POST` has no `If-Match`. So every creation (New note, Today, Calendar) first refreshes the target folder (a 304 when nothing changed) and looks the note up again. Offline, the note is created locally and queued; when the sync finds that the server already has it, the server wins as in [ADR 0003](0003-conflict-copy-server-wins.md), except that a local text identical to the freshly rendered Template is dropped instead of becoming a Conflict copy, since it holds nothing the user wrote.

## Considered options

- **Create first, reconcile after:** no network wait on tap, but leaves `(2)` duplicates that the user has to find and merge.
- **Block creation while offline:** never duplicates, but Today fails exactly when the phone is on a train.
- **Always make a Conflict copy:** ADR 0003 applied literally, but every offline Today that collides would show a conflict badge on a note the user never edited.
