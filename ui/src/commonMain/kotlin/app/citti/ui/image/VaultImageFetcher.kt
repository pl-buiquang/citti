package app.citti.ui.image

import app.citti.core.dav.ImageResolver
import app.citti.core.dav.ImageResult
import app.citti.core.dav.VaultPath
import coil3.ImageLoader
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import okio.Buffer

/** What Coil loads for an Image link of a Vault Note: the Note's Folder and the link as written (ADR 0008). */
data class VaultImageKey(
    val noteFolder: String,
    val link: String,
)

/** Why an Image could not be shown; the Preview maps it to "Image not found" or "Image not available offline". */
class ImageUnavailable(
    val reason: Reason,
) : Exception(reason.name) {
    enum class Reason { Missing, Offline, AuthFailed, Failed, Unsupported }

    /** True when the Image may exist and is only out of reach (offline, signed out, server error). */
    val offline: Boolean get() = reason == Reason.Offline || reason == Reason.AuthFailed || reason == Reason.Failed
}

/** An Image cached for 24 hours is shown as is; an older one is revalidated with its ETag on its next view. */
const val IMAGE_REVALIDATE_AFTER_MS: Long = 24L * 60 * 60 * 1000

/** What is kept next to the bytes of a cached Image (the disk entry's metadata file). */
data class CachedImageMeta(
    val fetchedAtMs: Long,
    val etag: String?,
    val contentType: String?,
) {
    fun encode(): String = "$fetchedAtMs\n${etag.orEmpty()}\n${contentType.orEmpty()}"

    fun isStale(nowMs: Long): Boolean = nowMs - fetchedAtMs >= IMAGE_REVALIDATE_AFTER_MS

    companion object {
        fun decode(text: String): CachedImageMeta? {
            val lines = text.split('\n')
            val at = lines.getOrNull(0)?.toLongOrNull() ?: return null
            return CachedImageMeta(at, lines.getOrNull(1)?.ifEmpty { null }, lines.getOrNull(2)?.ifEmpty { null })
        }
    }
}

/** The disk cache key of a Vault Image: its resolved path, so two Notes that link the same file share one entry. */
fun imageDiskKey(path: VaultPath): String = path.value

/**
 * Puts an Image just uploaded to [path] into the disk cache, as a fresh fetch would, so its new link shows at once
 * with no request. A cache that cannot take it (another writer, a full disk) is left as is.
 */
fun seedImageCache(
    cache: DiskCache,
    path: VaultPath,
    bytes: ByteArray,
    etag: String?,
    contentType: String?,
    nowMs: Long,
) {
    val editor = cache.openEditor(imageDiskKey(path)) ?: return
    try {
        cache.fileSystem.write(editor.data) { write(bytes) }
        cache.fileSystem.write(editor.metadata) { writeUtf8(CachedImageMeta(nowMs, etag, contentType).encode()) }
        editor.commit()
    } catch (e: Exception) {
        editor.abort()
    }
}

class VaultImageKeyer : Keyer<VaultImageKey> {
    override fun key(
        data: VaultImageKey,
        options: Options,
    ): String = "vault:${data.noteFolder}:${data.link}"
}

class VaultImageFetcherFactory(
    private val resolver: () -> ImageResolver?,
    private val clockMs: () -> Long,
) : Fetcher.Factory<VaultImageKey> {
    override fun create(
        data: VaultImageKey,
        options: Options,
        imageLoader: ImageLoader,
    ): Fetcher? = imageLoader.diskCache?.let { VaultImageFetcher(data, resolver, it, clockMs) }
}

/**
 * Loads a Vault Image through the [ImageResolver] and keeps it in Coil's disk cache under its resolved path
 * (spec 4.8). A cached copy is looked up first under the link's candidate paths, with no request, so it shows
 * offline; one older than [IMAGE_REVALIDATE_AFTER_MS] is revalidated with `If-None-Match` (a 304 keeps it) and
 * still shown when the server cannot be reached. Without a cached copy, a failure throws [ImageUnavailable].
 */
class VaultImageFetcher(
    private val data: VaultImageKey,
    private val resolver: () -> ImageResolver?,
    private val cache: DiskCache,
    private val clockMs: () -> Long,
) : Fetcher {
    private class Hit(
        val path: VaultPath,
        val snapshot: DiskCache.Snapshot,
        val meta: CachedImageMeta?,
    )

    override suspend fun fetch(): FetchResult {
        val resolver = resolver() ?: throw ImageUnavailable(ImageUnavailable.Reason.Offline)
        val hit = findCached(resolver)
        if (hit?.meta != null && !hit.meta.isStale(clockMs())) return serve(hit)
        if (hit != null) resolver.remember(data.noteFolder, data.link, hit.path)
        return when (val result = resolver.open(data.noteFolder, data.link, hit?.meta?.etag)) {
            is ImageResult.Loaded -> {
                hit?.snapshot?.close()
                store(result.path, result.bytes, result.etag, result.contentType)
            }

            is ImageResult.NotModified -> {
                hit?.let { renew(it) } ?: throw ImageUnavailable(ImageUnavailable.Reason.Failed)
            }

            ImageResult.Missing -> {
                hit?.let { serve(it) } ?: throw ImageUnavailable(ImageUnavailable.Reason.Missing)
            }

            ImageResult.Offline -> {
                hit?.let { serve(it) } ?: throw ImageUnavailable(ImageUnavailable.Reason.Offline)
            }

            ImageResult.AuthFailed -> {
                hit?.let { serve(it) } ?: throw ImageUnavailable(ImageUnavailable.Reason.AuthFailed)
            }

            ImageResult.Failed -> {
                hit?.let { serve(it) } ?: throw ImageUnavailable(ImageUnavailable.Reason.Failed)
            }

            is ImageResult.Web, ImageResult.Unsupported -> {
                hit?.snapshot?.close()
                throw ImageUnavailable(ImageUnavailable.Reason.Unsupported)
            }
        }
    }

    private fun findCached(resolver: ImageResolver): Hit? {
        for (path in resolver.offlineCandidates(data.noteFolder, data.link)) {
            val snapshot = cache.openSnapshot(imageDiskKey(path)) ?: continue
            val meta = runCatching { cache.fileSystem.read(snapshot.metadata) { readUtf8() } }.getOrNull()?.let(CachedImageMeta::decode)
            return Hit(path, snapshot, meta)
        }
        return null
    }

    private fun serve(hit: Hit): FetchResult =
        SourceFetchResult(
            source =
                ImageSource(
                    file = hit.snapshot.data,
                    fileSystem = cache.fileSystem,
                    diskCacheKey = imageDiskKey(hit.path),
                    closeable = hit.snapshot,
                ),
            mimeType = hit.meta?.contentType,
            dataSource = coil3.decode.DataSource.DISK,
        )

    /** A 304: the same bytes with a new fetch time. */
    private fun renew(hit: Hit): FetchResult {
        val bytes = cache.fileSystem.read(hit.snapshot.data) { readByteArray() }
        hit.snapshot.close()
        return store(hit.path, bytes, hit.meta?.etag, hit.meta?.contentType)
    }

    private fun store(
        path: VaultPath,
        bytes: ByteArray,
        etag: String?,
        contentType: String?,
    ): FetchResult {
        val meta = CachedImageMeta(clockMs(), etag, contentType)
        val key = imageDiskKey(path)
        val snapshot =
            cache.openEditor(key)?.let { editor ->
                try {
                    cache.fileSystem.write(editor.data) { write(bytes) }
                    cache.fileSystem.write(editor.metadata) { writeUtf8(meta.encode()) }
                    editor.commitAndOpenSnapshot()
                } catch (e: Exception) {
                    editor.abort()
                    null
                }
            }
        if (snapshot == null) {
            // The cache could not take it (another writer, a full disk): show it once from memory.
            return SourceFetchResult(
                ImageSource(Buffer().write(bytes), cache.fileSystem),
                contentType,
                coil3.decode.DataSource.NETWORK,
            )
        }
        return serve(Hit(path, snapshot, meta))
    }
}
