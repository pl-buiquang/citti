package app.citti.ui.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** The JVM target has no camera or gallery: every pick is cancelled. */
@Composable
actual fun rememberImagePicker(
    nowMillis: () -> Long,
    onResult: (PickedImage?) -> Unit,
): ImagePicker {
    val current by rememberUpdatedState(onResult)
    return remember {
        object : ImagePicker {
            override fun launch(source: PickSource) = current(null)
        }
    }
}
