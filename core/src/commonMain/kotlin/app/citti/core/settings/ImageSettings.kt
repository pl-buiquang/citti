package app.citti.core.settings

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.citti.core.db.CittiDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** `setting` key of the Image cache size, in megabytes. */
const val KEY_IMAGES_CACHE_MB = "images.cache-mb"

const val DEFAULT_IMAGE_CACHE_MB = 500

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

    private fun parse(raw: String?): Int = raw?.toIntOrNull()?.takeIf { it in IMAGE_CACHE_CHOICES_MB } ?: DEFAULT_IMAGE_CACHE_MB
}
