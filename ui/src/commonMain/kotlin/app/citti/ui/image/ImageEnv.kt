package app.citti.ui.image

import app.citti.core.dav.ImageResolver
import app.citti.core.dav.ImageSource
import coil3.ImageLoader

/** What the Preview and the Image viewer need to show the Images of one Note. */
class ImageEnv(
    val loader: ImageLoader,
    private val resolver: ImageResolver,
    val noteFolder: String,
    /** A tap on an Image of the Preview: the link as written. */
    val onOpen: (String) -> Unit = {},
) {
    /** The Coil model of [link]: the https URL itself, a [VaultImageKey], or null for a scheme that is not loaded. */
    fun model(link: String): Any? =
        when (val first = resolver.candidates(noteFolder, link, null).firstOrNull()) {
            is ImageSource.Web -> first.url
            is ImageSource.Vault -> VaultImageKey(noteFolder, link)
            ImageSource.Unsupported, null -> null
        }
}

/** The file name shown for an Image link: the last path segment, without escapes or a query. */
fun imageFileName(link: String): String =
    link
        .substringBefore('#')
        .substringBefore('?')
        .substringAfterLast('/')
        .replace("%20", " ")
