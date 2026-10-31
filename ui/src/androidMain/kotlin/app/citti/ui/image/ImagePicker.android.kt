package app.citti.ui.image

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The FileProvider of the app (`res/xml/file_paths.xml`), where the camera app writes its shot. */
private fun Context.filesAuthority() = "$packageName.files"

private val shotTime = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

/** `IMG_YYYYMMDD_HHmmss` in local time. */
private fun imageBaseName(nowMillis: Long) = "IMG_" + shotTime.format(Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()))

@Composable
actual fun rememberImagePicker(
    nowMillis: () -> Long,
    onResult: (PickedImage?) -> Unit,
): ImagePicker {
    val context = LocalContext.current.applicationContext
    val current by rememberUpdatedState(onResult)
    val clock by rememberUpdatedState(nowMillis)
    // The file the camera app writes to; saved, so the result still finds it after the activity was recreated.
    var shotPath by rememberSaveable { mutableStateOf<String?>(null) }

    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
            val file = shotPath?.let(::File)
            shotPath = null
            if (taken && file != null && file.length() > 0) {
                current(CameraShot(file))
            } else {
                file?.delete()
                current(null)
            }
        }
    val gallery =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            current(uri?.let { GalleryPick(context, it, clock()) })
        }
    return remember {
        object : ImagePicker {
            override fun launch(source: PickSource) {
                try {
                    when (source) {
                        PickSource.Camera -> {
                            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                            val file = File(dir, imageBaseName(clock()) + ".jpg")
                            shotPath = file.path
                            camera.launch(FileProvider.getUriForFile(context, context.filesAuthority(), file))
                        }

                        PickSource.Gallery -> {
                            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    }
                } catch (e: ActivityNotFoundException) {
                    shotPath?.let { File(it).delete() }
                    shotPath = null
                    current(null)
                }
            }
        }
    }
}

/** A camera shot in `cacheDir/camera/`: deleted once uploaded (or not). */
private class CameraShot(
    private val file: File,
) : PickedImage {
    override suspend fun load(): LoadedImage = withContext(Dispatchers.IO) { LoadedImage(file.readBytes(), file.name, "image/jpeg") }

    override fun dispose() {
        file.delete()
    }
}

/** A Photo Picker pick: its display name (or `IMG_<time>.<ext>` when it has none) and its MIME type. */
private class GalleryPick(
    private val context: Context,
    private val uri: Uri,
    private val pickedAtMillis: Long,
) : PickedImage {
    override suspend fun load(): LoadedImage =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val display =
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            val name =
                display?.takeIf { it.isNotBlank() }
                    ?: (imageBaseName(pickedAtMillis) + (MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { ".$it" } ?: ""))
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("Cannot read $uri")
            LoadedImage(bytes, name, mime)
        }

    override fun dispose() = Unit
}
