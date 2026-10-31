# Use the Notes API for text and WebDAV for images

Note text, folders and favorites go through the Nextcloud Notes API (incremental, ETag-based sync, server-side favorite flag). Images are fetched and uploaded over WebDAV. The Notes attachment endpoint resolves paths relative to the note's own folder, while the vault is an Obsidian vault whose links are relative to a shared `media/` folder (`![x|900](supernote/a.png)`), so the Notes API alone cannot show or add the images the user actually has.

## Considered options

- **WebDAV only:** sees every file exactly like Obsidian, but loses the server favorite flag and the Notes API's cheap incremental sync.
- **Notes API only:** simplest, but breaks image preview.
