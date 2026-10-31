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
A `[[title]]` reference in a note that leads to another note.
_Avoid_: Internal link, backlink

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

**Conflict copy**:
A new note holding the user's local text, created when the same note changed elsewhere in the meantime.
_Avoid_: Backup, duplicate

## Browsing

**List mode**:
The flat main view, favorites first then most recently modified.

**Tree mode**:
The main view organized by folders, which the user navigates level by level.
_Avoid_: Folder view

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
