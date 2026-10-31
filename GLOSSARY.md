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
The month view that shows which days have a Daily note and opens or creates one.
_Avoid_: Agenda, date picker

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

## Syncing

**Sync**:
One run that sends the user's pending changes to the server, then fetches what changed there.
_Avoid_: Refresh, update

**Pending change**:
An edit, new note or deletion made on the phone that the server does not have yet.
_Avoid_: Dirty note, unsynced edit, queued edit

**Account**:
The server address and the user that the app is signed in with.
_Avoid_: Profile, login

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
A home-screen widget that shows one chosen note and opens it for editing.

**Favorites widget**:
A home-screen widget that lists the favorites and opens the tapped one.

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
