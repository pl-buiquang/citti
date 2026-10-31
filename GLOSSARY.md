# citti

A personal app for reading and editing the notes of a Nextcloud-hosted markdown vault on a phone.

## Language

**Note**:
One markdown document, identified by its title and the folder it lives in.
_Avoid_: Document, page, file

**Vault**:
The whole collection of notes and images the user keeps on their Nextcloud.
_Avoid_: Library, workspace

**Folder**:
A level of the vault's nested hierarchy that contains notes and other folders.
_Avoid_: Category, directory

**Favorite**:
A note the user has marked to keep at the top of the list and in the Favorites widget.
_Avoid_: Pin, star, bookmark

**Image**:
A picture stored in the vault's attachment folder and shown inside a note.
_Avoid_: Attachment, media

**Wikilink**:
A `[[target]]` or `[[target|alias]]` reference in a note that leads to another note; the target is a title or a folder path and title (`journal/2026-W44`), and the alias is the text shown.
_Avoid_: Internal link, backlink

## Journal

**Daily note**:
The note for one calendar date, kept in the Journal folder and named after that date.
_Avoid_: Journal entry, diary page

**Journal folder**:
The folder that holds the Daily notes.
_Avoid_: Diary folder

**Template**:
A note whose text, with its placeholders filled in, becomes the starting text of a new note.
_Avoid_: Snippet, boilerplate

**Calendar**:
The third main view, next to List and Tree mode, that shows which days have a Daily note and opens or creates one.
_Avoid_: Agenda, date picker

**Today button**:
The button next to New note that opens or creates today's Daily note.
_Avoid_: Daily button, journal button

**Date format**:
The pattern, in Moment.js tokens, that turns a date into a Daily note's title.
_Avoid_: Date pattern, file name format

**Date locale**:
The language used for month and weekday names when a Template is filled in; detected from the journal unless set by hand.
_Avoid_: Locale (alone), language

## Visibility

**Unsynced folder**:
A folder the user excluded so its notes are never downloaded or searched.
_Avoid_: Ignored folder, excluded folder

**Hidden folder**:
A folder whose notes are synced and searchable but not shown in the list or tree.
_Avoid_: Archived folder, excluded folder

## Editing

**Edit mode**:
The default way of showing a note: raw markdown, ready to type.

**Preview mode**:
The rendered view of a note, with images, links and folded frontmatter.
_Avoid_: Read mode, view mode

**Properties**:
The frontmatter of a note (the YAML block between `---` lines at the top), shown folded in Preview mode.
_Avoid_: Metadata, front matter, header

**In-note search**:
Finding text inside the open note, with a match count and previous/next, in Edit or Preview mode.
_Avoid_: Find, find in page

**Image viewer**:
The full-screen view of one Image, opened by tapping it in Preview mode.
_Avoid_: Lightbox, gallery

**Save indicator**:
The line under a note's title saying whether its text is saved, pending, syncing or offline.
_Avoid_: Sync status (that is the whole app's sync)

**Conflict copy**:
A new note holding the user's local text, created when the same note changed elsewhere in the meantime.
_Avoid_: Backup, duplicate

**Note info**:
The sheet that shows a note's folder, dates, size and sync state.
_Avoid_: Properties (that is the frontmatter), details

## Syncing

**Sync**:
One run that sends the user's pending changes to the server, then fetches what changed there.
_Avoid_: Refresh, update

**Pending change**:
An edit, new note or deletion made on the phone that the server does not have yet.
_Avoid_: Dirty note, unsynced edit, queued edit

**Folder refresh**:
Asking the server for the notes of one folder only, before creating, renaming or moving a note there.
_Avoid_: Partial sync, folder sync

**Untouched note**:
A new note whose text is still exactly the text it was created with, so dropping it loses nothing the user wrote.
_Avoid_: Empty note, blank note

**Account**:
The server address and the user that the app is signed in with.
_Avoid_: Profile, login

**Fixture Vault**:
The synthetic Vault the E2E tests seed into the fake server; never real notes.
_Avoid_: Test data, sample vault

## Browsing

**List mode**:
The flat main view, favorites first then most recently modified.
_Avoid_: Flat view, all notes

**Tree mode**:
The main view organized by folders, which the user navigates level by level.
_Avoid_: Folder view

**Vault root**:
The top folder of the vault, holding the notes that are in no folder; Tree mode labels it "Notes".
_Avoid_: Root category, home

**Breadcrumb**:
The folder path shown at the top of Tree mode, one tappable level per folder, starting at the vault root.
_Avoid_: Path bar

**Excerpt**:
The first meaningful line of a note's text, shown under its title in a list, skipping frontmatter and a heading that repeats the title.
_Avoid_: Preview (that is Preview mode), summary, snippet

**Search**:
Finding notes by the words of their title, text or folder path, across every synced note, Hidden folders included.
_Avoid_: Filter, find, lookup

## Widgets

**Note widget**:
A home-screen widget that shows one chosen note from its Reading position and opens it there.

**Note picker**:
The screen shown when a Note widget is placed or reconfigured, to choose its note. It searches every synced note.
_Avoid_: Chooser, selector

**Reading position**:
The line at the top of the Note screen when you last left a note. Kept on this device only. The Note widget starts there.
_Avoid_: Scroll position, bookmark

**Favorites widget**:
A home-screen widget that lists the favorites, with today's Daily note pinned first, and opens the tapped one.

**Daily note widget**:
A home-screen widget that shows today's Daily note from its Reading position and opens it there, creating it when missing.
_Avoid_: Journal widget, today widget

**Deep link**:
A `citti://note/<id>` or `citti://daily` link that opens one note, or today's Daily note, in the app. The widgets use them.

## Appearance

**Theme**:
A named set of design tokens and theme roles that gives the app its look.
_Avoid_: Skin, style

**Preset theme**:
One of the themes shipped with the app: Signal, Paper or Index.
_Avoid_: Built-in theme, default theme

**Custom theme**:
The user's one own theme, made of a base theme and overrides.
_Avoid_: User theme, personal theme

**Base theme**:
The preset theme a custom theme starts from and falls back to for every value it does not override.
_Avoid_: Parent theme, template

**Override**:
A value the custom theme sets in place of the base theme's.
_Avoid_: Tweak, patch

**Mode**:
Light, dark, or following the phone (System), chosen apart from the theme.
_Avoid_: Dark theme, color scheme

**Accent source**:
Where the accent colors come from: the theme, or the wallpaper (Dynamic color).
_Avoid_: Accent mode

**Density**:
How much room rows and gutters take: Compact, Default or Comfortable.
_Avoid_: Spacing, size

**Theme file**:
The JSON file that holds a custom theme (its base theme and overrides), exported and imported through the file picker.
_Avoid_: Theme export, theme preset file

**Text size**:
A scale from 85 % to 130 % applied to every text in the app and widgets, on top of the system font size.
_Avoid_: Font size, zoom
