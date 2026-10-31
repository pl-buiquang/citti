# Images are resolved by trying a few candidate paths over WebDAV

The vault is an Obsidian vault: most Image links are written relative to the attachment folder (`![Page 87|900](supernote/a.png)` means `media/supernote/a.png`), some are relative to the note (`../../media/x.png`), and a few are bare file names next to the note. Obsidian resolves them with its index of every file in the vault. citti only syncs notes through the Notes API, so it has no such index. It resolves a relative link by trying, in order, the note's folder, the vault root and the attachment folder (read from `.obsidian/app.json`, default `media`), with an authenticated WebDAV GET; the first that exists wins and is remembered for the session, and a miss is not retried in that session. The cache is keyed by the resolved path, so a cached Image shows offline with no request. The cost is that an Image Obsidian finds by file name alone, somewhere else in the vault, shows as missing.

## Considered options

- **A file index from a PROPFIND of the vault:** resolves exactly like Obsidian, but needs a recursive listing (often disabled for `Depth: infinity`, or ~1,100 files over many requests) and a second sync to keep it fresh.
- **Ordered candidates, then the index as a fallback:** most faithful, but carries both costs; worth it only if missing Images show up in practice.
