package app.citti.ui.create

import app.citti.core.dav.VaultPath
import app.citti.ui.note.LinkRef

/** What the New note dialog opens with for "Create note" on an unresolved link. */
data class NewNotePrefill(
    val folder: String,
    val title: String,
)

/**
 * The New note prefill for [ref], a link in a Note of [currentFolder] that resolved to nothing (M5 spec §3). The title
 * is the target's last segment, with the alias, `#heading`, `^block` and `.md` stripped and percent escapes decoded,
 * as `LinkResolver` reads it. The folder is the target's path part: from the Vault root for a wikilink (bare `[[T]]`
 * goes to [currentFolder]), relative to [currentFolder] for a markdown link.
 */
fun linkPrefill(
    ref: LinkRef,
    currentFolder: String,
): NewNotePrefill =
    when (ref) {
        is LinkRef.Wiki -> {
            val target = strip(ref.target.substringBefore('|'))
            val path = VaultPath.resolve("", target)
            when {
                path == null -> NewNotePrefill(currentFolder, "")
                '/' in path.value -> NewNotePrefill(path.folder, title(path.name))
                else -> NewNotePrefill(currentFolder, title(path.name))
            }
        }

        is LinkRef.Markdown -> {
            val path = VaultPath.resolve(currentFolder, strip(ref.url.substringBefore('?')))
            if (path == null) NewNotePrefill(currentFolder, "") else NewNotePrefill(path.folder, title(path.name))
        }
    }

private fun strip(target: String): String = target.substringBefore('#').substringBefore('^').trim()

private fun title(name: String): String = name.trim().let { if (it.endsWith(".md", ignoreCase = true)) it.dropLast(3) else it }.trim()
