package app.citti.core.dav

import app.citti.core.api.ApiError
import app.citti.core.api.ApiResult

sealed interface ImageSource {
    data class Vault(
        val path: VaultPath,
    ) : ImageSource

    data class Web(
        val url: String,
    ) : ImageSource

    data object Unsupported : ImageSource
}

sealed interface ImageResult {
    class Loaded(
        val path: VaultPath,
        val bytes: ByteArray,
        val etag: String?,
        val contentType: String?,
    ) : ImageResult

    data class NotModified(
        val path: VaultPath,
    ) : ImageResult

    /** Load it directly over https, without credentials. */
    data class Web(
        val url: String,
    ) : ImageResult

    data object Unsupported : ImageResult

    /** No candidate exists on the server. */
    data object Missing : ImageResult

    data object Offline : ImageResult

    data object AuthFailed : ImageResult

    /** A server error on a candidate; try again later. */
    data object Failed : ImageResult
}

/**
 * Turns an Image link of a Note into a file of the Vault (ADR 0008): the Note's folder, then the Vault root, then
 * the attachment folder, over WebDAV. The first that exists wins and is remembered for the session; a 404 is not
 * asked again in that session.
 */
class ImageResolver(
    private val dav: DavClient,
    private val config: ObsidianConfig,
) {
    private val winners = HashMap<Pair<String, String>, VaultPath>()
    private val misses = HashSet<VaultPath>()

    /** The Vault candidates in order (duplicates and paths leaving the Vault dropped), or the one non-Vault source. */
    fun candidates(
        noteFolder: String,
        link: String,
        attachmentFolder: String? = DEFAULT_ATTACHMENTS,
    ): List<ImageSource> {
        val scheme = link.substringBefore(':', "").takeIf { it.isNotEmpty() && '/' !in it && it.all { c -> c.isLetter() } }
        if (scheme != null && scheme.length > 1) {
            return listOf(
                if (scheme.equals("http", true) ||
                    scheme.equals("https", true)
                ) {
                    ImageSource.Web(link)
                } else {
                    ImageSource.Unsupported
                },
            )
        }
        val paths =
            listOfNotNull(
                VaultPath.resolve(noteFolder, link),
                VaultPath.resolve("", link),
                attachmentFolder?.let {
                    VaultPath.resolve(
                        if (it.startsWith("./")) noteFolder + "/" + it.removePrefix("./") else it,
                        link,
                    )
                },
            )
        return paths.distinct().map { ImageSource.Vault(it) }
    }

    /** The path remembered for this link in this session, if any (a cached Image can then show with no request). */
    fun known(
        noteFolder: String,
        link: String,
    ): VaultPath? = winners[noteFolder to link]

    /**
     * The Vault paths this link can be at, with no request: the remembered one first, then the candidates with the
     * attachment folder kept from an earlier read. A cached Image is looked up under these keys, so it shows offline.
     */
    fun offlineCandidates(
        noteFolder: String,
        link: String,
    ): List<VaultPath> =
        (
            listOfNotNull(known(noteFolder, link)) +
                candidates(noteFolder, link, config.storedAttachmentFolder()).filterIsInstance<ImageSource.Vault>().map { it.path }
        ).distinct()
            .filter { it !in misses }

    /** Remembers [path] as the one this link resolved to (a cached copy was found there), so a revalidation can send its ETag. */
    fun remember(
        noteFolder: String,
        link: String,
        path: VaultPath,
    ) {
        val key = noteFolder to link
        if (path !in misses && key !in winners) winners[key] = path
    }

    suspend fun open(
        noteFolder: String,
        link: String,
        ifNoneMatch: String? = null,
    ): ImageResult {
        // Web and unsupported links need no Vault access, so decide on them before reading the config.
        when (val first = candidates(noteFolder, link, null).firstOrNull()) {
            null -> return ImageResult.Missing
            is ImageSource.Web -> return ImageResult.Web(first.url)
            ImageSource.Unsupported -> return ImageResult.Unsupported
            is ImageSource.Vault -> Unit
        }
        val list = candidates(noteFolder, link, config.attachmentFolder())
        val key = noteFolder to link
        val ordered = listOfNotNull(winners[key]) + list.filterIsInstance<ImageSource.Vault>().map { it.path }.filter { it != winners[key] }
        for (path in ordered) {
            if (path in misses) continue
            when (val result = dav.get(path, if (path == winners[key]) ifNoneMatch else null)) {
                is ApiResult.Success -> {
                    winners[key] = path
                    return when (val v = result.value) {
                        is DavGet.Body -> ImageResult.Loaded(path, v.bytes, v.etag, v.contentType)
                        DavGet.NotModified -> ImageResult.NotModified(path)
                    }
                }

                is ApiResult.Failure -> {
                    when (result.error) {
                        ApiError.NotFound -> {
                            misses += path
                            if (winners[key] == path) winners.remove(key)
                        }

                        is ApiError.Auth -> {
                            return ImageResult.AuthFailed
                        }

                        is ApiError.Network, ApiError.DavNotReady -> {
                            return ImageResult.Offline
                        }

                        else -> {
                            return ImageResult.Failed
                        }
                    }
                }
            }
        }
        return ImageResult.Missing
    }
}
