package app.citti.core.settings

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.citti.core.dav.VaultPath
import app.citti.core.db.CittiDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** `setting` key of the Image cache size, in megabytes. */
const val KEY_IMAGES_CACHE_MB = "images.cache-mb"

const val DEFAULT_IMAGE_CACHE_MB = 500

/** `setting` key of the folder Images are uploaded to; absent means the attachment folder of Obsidian. */
const val KEY_IMAGES_UPLOAD_FOLDER = "images.upload-folder"

/** `setting` key of [RemoteImages]. */
const val KEY_IMAGES_REMOTE = "images.remote"

/** Whether Preview fetches the `http(s)` Images of a Note on its own. Ask shows a placeholder that loads on a tap. */
enum class RemoteImages(
    val id: String,
) {
    ASK("ask"),
    ALWAYS("always"),
    ;

    companion object {
        /** The choice stored as [id]; anything else is [ASK]. */
        fun of(id: String?): RemoteImages = entries.firstOrNull { it.id == id } ?: ASK
    }
}

/** The sizes Settings › Images offers, in megabytes. */
val IMAGE_CACHE_CHOICES_MB = listOf(250, DEFAULT_IMAGE_CACHE_MB, 1000, 2000)

/** Reads and writes the Image settings in the `setting` table. A stored value that is not a choice reads as the default. */
class ImageSettings(
    db: CittiDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val queries = db.settingQueries

    fun cacheMb(): Int = parse(queries.selectSetting(KEY_IMAGES_CACHE_MB).executeAsOneOrNull())

    fun setCacheMb(mb: Int) {
        require(mb in IMAGE_CACHE_CHOICES_MB) { "Unsupported Image cache size: $mb" }
        queries.upsertSetting(KEY_IMAGES_CACHE_MB, mb.toString())
    }

    fun cacheMbFlow(): Flow<Int> =
        queries
            .selectSetting(KEY_IMAGES_CACHE_MB)
            .asFlow()
            .mapToOneOrNull(dispatcher)
            .map(::parse)

    fun remoteImages(): RemoteImages = RemoteImages.of(queries.selectSetting(KEY_IMAGES_REMOTE).executeAsOneOrNull())

    fun remoteImagesFlow(): Flow<RemoteImages> =
        queries
            .selectSetting(KEY_IMAGES_REMOTE)
            .asFlow()
            .mapToOneOrNull(dispatcher)
            .map(RemoteImages::of)

    fun setRemoteImages(choice: RemoteImages) {
        queries.upsertSetting(KEY_IMAGES_REMOTE, choice.id)
    }

    /** The upload folder set by the user (a Vault path), or null to follow Obsidian. */
    fun uploadFolder(): String? = parseFolder(queries.selectSetting(KEY_IMAGES_UPLOAD_FOLDER).executeAsOneOrNull())

    fun uploadFolderFlow(): Flow<String?> =
        queries
            .selectSetting(KEY_IMAGES_UPLOAD_FOLDER)
            .asFlow()
            .mapToOneOrNull(dispatcher)
            .map(::parseFolder)

    /**
     * Overrides the upload folder with a Vault path (`/` trimmed); null or blank resets it to follow Obsidian. A path
     * that leaves the Vault root is refused; the caller validates names and Unsynced folders first.
     */
    fun setUploadFolder(folder: String?) {
        if (folder.isNullOrBlank()) {
            queries.deleteSetting(KEY_IMAGES_UPLOAD_FOLDER)
            return
        }
        val path = requireNotNull(parseFolder(folder)) { "Not a folder of the Vault: $folder" }
        queries.upsertSetting(KEY_IMAGES_UPLOAD_FOLDER, path)
    }

    private fun parseFolder(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && it.split('/').none { s -> s == ".." || s == "." } }?.let { VaultPath.parse(it)?.value }

    private fun parse(raw: String?): Int = raw?.toIntOrNull()?.takeIf { it in IMAGE_CACHE_CHOICES_MB } ?: DEFAULT_IMAGE_CACHE_MB
}
