package app.citti.ui.image

import app.citti.core.dav.ImageResolver
import app.citti.core.dav.ImageSource
import app.citti.core.settings.RemoteImages
import coil3.ImageLoader

/** What the Preview and the Image viewer need to show the Images of one Note. */
class ImageEnv(
    val loader: ImageLoader,
    private val resolver: ImageResolver,
    val noteFolder: String,
    /** A tap on an Image of the Preview: the link as written. */
    val onOpen: (String) -> Unit = {},
    /** Whether a web Image loads on its own ([RemoteImages.ALWAYS]) or after a tap. */
    val remote: RemoteImages = RemoteImages.ASK,
) {
    /** What Preview does with [link]: show it, ask before fetching a web Image, or show the file name only. */
    fun choice(link: String): ImageChoice =
        when (val first = resolver.candidates(noteFolder, link, null).firstOrNull()) {
            is ImageSource.Web -> if (remote == RemoteImages.ALWAYS) ImageChoice.Load(first.url) else ImageChoice.AskRemote(first.url)
            is ImageSource.Vault -> ImageChoice.Load(VaultImageKey(noteFolder, link))
            ImageSource.Unsupported, null -> ImageChoice.Unsupported
        }

    /** The Coil model of [link]: the https URL itself, a [VaultImageKey], or null for a scheme that is not loaded. */
    fun model(link: String): Any? =
        when (val first = resolver.candidates(noteFolder, link, null).firstOrNull()) {
            is ImageSource.Web -> first.url
            is ImageSource.Vault -> VaultImageKey(noteFolder, link)
            ImageSource.Unsupported, null -> null
        }
}

/** The decision of [ImageEnv.choice]. */
sealed interface ImageChoice {
    /** Load [model] with Coil now. */
    data class Load(
        val model: Any,
    ) : ImageChoice

    /** A web Image at [url]: nothing is fetched until the reader taps. */
    data class AskRemote(
        val url: String,
    ) : ImageChoice

    data object Unsupported : ImageChoice
}

/** The file name shown for an Image link: the last path segment, without escapes or a query. */
fun imageFileName(link: String): String =
    link
        .substringBefore('#')
        .substringBefore('?')
        .substringAfterLast('/')
        .replace("%20", " ")

/** The host of a web Image URL, shown on its placeholder. */
fun remoteHost(url: String): String =
    url
        .substringAfter("://")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfter('@')
