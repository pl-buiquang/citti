package app.citti.core.fake

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A Note of the [FixtureVault]: [path] is its Vault path (`work/projects/alpha.md`). */
data class FixtureNote(
    val path: String,
    val title: String,
    val category: String,
    val content: String,
    val favorite: Boolean,
    /** Epoch seconds. */
    val modified: Long,
    /** A fixed server id, when a test needs a stable one. */
    val id: Long?,
)

/**
 * The Fixture Vault: the synthetic Vault the E2E tests seed into the fake server; never real notes.
 *
 * It lives in the classpath resources under `fixtures/<name>/`. Each `.md` file is a Note (its folder is the
 * category, its file name without `.md` the title); other files (Images, `.obsidian/`) are served by the fake
 * WebDAV only. `fixture.json` lists every file and holds what a file can't say: `favorite`, `modified`, `id`.
 *
 * An APK drops resource folders whose name starts with a dot, so a Vault folder `.x` is stored as `dot-x`
 * ([resourcePath]); `fixture.json` and the loaded Vault use the real Vault paths.
 */
class FixtureVault(
    val notes: List<FixtureNote>,
    /** Non-note files by Vault path. */
    val files: Map<String, ByteArray>,
) {
    fun note(title: String): FixtureNote = notes.singleOrNull { it.title == title } ?: error("no single fixture note titled $title")

    companion object {
        fun load(name: String = "vault"): FixtureVault {
            fun read(path: String): ByteArray =
                FixtureVault::class.java.getResourceAsStream("/fixtures/$name/${resourcePath(path)}")?.use { it.readBytes() }
                    ?: error("fixture file $name/$path is not on the classpath")

            val index = Json.parseToJsonElement(read("fixture.json").decodeToString()).jsonObject
            val meta = index["notes"]?.jsonObject ?: JsonObject(emptyMap())
            val paths = index.getValue("files").jsonArray.map { it.jsonPrimitive.content }
            val notes =
                paths.filter { it.endsWith(".md") }.map { path ->
                    val m = meta[path]?.jsonObject
                    FixtureNote(
                        path = path,
                        title = path.substringAfterLast('/').removeSuffix(".md"),
                        category = path.substringBeforeLast('/', ""),
                        content = read(path).decodeToString(),
                        favorite = m?.get("favorite")?.jsonPrimitive?.booleanOrNull ?: false,
                        modified = m?.get("modified")?.jsonPrimitive?.longOrNull ?: error("$path has no modified time"),
                        id = m?.get("id")?.jsonPrimitive?.longOrNull,
                    )
                }
            val files = paths.filterNot { it.endsWith(".md") }.associateWith(::read)
            return FixtureVault(notes, files)
        }

        /** Where the Vault file [vaultPath] lives in the resources: `.obsidian/app.json` is `dot-obsidian/app.json`. */
        fun resourcePath(vaultPath: String): String =
            vaultPath.split('/').joinToString("/") { if (it.startsWith('.')) "dot-" + it.drop(1) else it }
    }
}

/**
 * Puts every Note of [vault] on the server (fixed ids first, so the others never take them) and its other files
 * in the fake WebDAV. Returns the server id of each Note by Vault path.
 */
fun FakeNotesServer.seed(vault: FixtureVault = FixtureVault.load()): Map<String, Long> {
    val (fixed, free) = vault.notes.partition { it.id != null }
    val ids =
        (fixed + free).associate { n ->
            n.path to addNote(n.title, n.content, n.category, n.favorite, n.modified, fixedId = n.id)
        }
    vault.files.forEach { (path, bytes) -> putFile(path, bytes) }
    return ids
}
