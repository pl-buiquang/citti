package app.citti.ui.image

/**
 * The Image cache as Settings › Images sees it. The app implements it over Coil's disk and memory caches, off the
 * main thread. The size limit is a setting ([app.citti.core.settings.ImageSettings]) that the cache is built with
 * the next time the app starts.
 */
interface ImageCache {
    /** Bytes on disk. */
    suspend fun size(): Long

    /** Empties the disk and memory caches. */
    suspend fun clear()
}
