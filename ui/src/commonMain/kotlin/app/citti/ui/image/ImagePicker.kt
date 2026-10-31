package app.citti.ui.image

import androidx.compose.runtime.Composable
import app.citti.core.dav.UploadResult

/** Where an inserted Image comes from: the system camera app or the system Photo Picker. */
enum class PickSource { Camera, Gallery }

/** The file of a [PickedImage], read off the main thread. */
class LoadedImage(
    val bytes: ByteArray,
    /** The display name of a gallery pick, `IMG_YYYYMMDD_HHmmss.jpg` for a camera shot. */
    val name: String,
    val mimeType: String,
)

/** An Image the user picked or shot, not read yet. [dispose] once done with it (deletes a camera shot's file). */
interface PickedImage {
    suspend fun load(): LoadedImage

    fun dispose()
}

/** Opens the system camera or Photo Picker; the result goes to the `onResult` of [rememberImagePicker]. */
interface ImagePicker {
    fun launch(source: PickSource)
}

/**
 * The platform picker of Insert image. [onResult] gets the Image, or null when the user cancelled (or no app could
 * take the request). [nowMillis] names camera shots. Android: the AndroidX Activity result contracts, with no
 * permission (`TakePicture` into a FileProvider file, `PickVisualMedia(ImageOnly)`).
 */
@Composable
expect fun rememberImagePicker(
    nowMillis: () -> Long,
    onResult: (PickedImage?) -> Unit,
): ImagePicker

/** Uploads a picked Image into the Vault (`ImageUploader`) for a Note in [noteFolder], and seeds the Image cache. */
fun interface ImageUpload {
    suspend fun upload(
        image: LoadedImage,
        noteFolder: String,
    ): UploadResult
}
