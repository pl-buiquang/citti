package app.citti.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import app.citti.ui.components.CittiIcon
import app.citti.ui.components.IconButton
import app.citti.ui.components.colored
import app.citti.ui.image.ImageEnv
import app.citti.ui.image.ImageUnavailable
import app.citti.ui.image.ZoomState
import app.citti.ui.image.imageFileName
import app.citti.ui.resources.Res
import app.citti.ui.resources.image_missing
import app.citti.ui.resources.image_offline
import app.citti.ui.resources.image_viewer_close
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSpacing
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.request.ImageRequest
import org.jetbrains.compose.resources.stringResource

/**
 * The full-screen view of one Image ([link] of a Note), opened from Preview mode. It uses the same loader and cache
 * as the Preview, so an Image already seen opens offline. The Image is decoded at up to twice the screen size;
 * pinch to zoom from 1x to 5x, double-tap toggles 2x, drag to pan. The bar on top is translucent over the Image.
 */
@Composable
fun ImageViewerScreen(
    link: String,
    env: ImageEnv,
    onClose: () -> Unit,
) {
    val colors = CittiTheme.colors
    val name = imageFileName(link)
    var zoom by remember { mutableStateOf(ZoomState()) }
    var intrinsicW by remember { mutableStateOf(0f) }
    var intrinsicH by remember { mutableStateOf(0f) }
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .background(colors.scrim),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewW = constraints.maxWidth.toFloat()
            val viewH = constraints.maxHeight.toFloat()
            val (contentW, contentH) = ZoomState.fitted(viewW, viewH, intrinsicW, intrinsicH)
            val model = env.model(link)
            if (model == null) {
                Message(stringResource(Res.string.image_missing))
            } else {
                val request =
                    ImageRequest
                        .Builder(LocalPlatformContext.current)
                        .data(model)
                        .size(constraints.maxWidth * DECODE_FACTOR, constraints.maxHeight * DECODE_FACTOR)
                        .build()
                SubcomposeAsyncImage(
                    model = request,
                    contentDescription = name,
                    imageLoader = env.loader,
                    contentScale = ContentScale.Fit,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .pointerInput(contentW, contentH) {
                                detectTransformGestures { centroid, pan, factor, _ ->
                                    zoom = zoom.transform(centroid.x, centroid.y, pan.x, pan.y, factor, viewW, viewH, contentW, contentH)
                                }
                            }.pointerInput(contentW, contentH) {
                                detectTapGestures(onDoubleTap = { zoom = zoom.toggle(it.x, it.y, viewW, viewH, contentW, contentH) })
                            }.graphicsLayer {
                                scaleX = zoom.scale
                                scaleY = zoom.scale
                                translationX = zoom.offsetX
                                translationY = zoom.offsetY
                            },
                    onSuccess = {
                        intrinsicW = it.painter.intrinsicSize.width
                        intrinsicH = it.painter.intrinsicSize.height
                    },
                    loading = {},
                    error = { state ->
                        val offline = (state.result.throwable as? ImageUnavailable)?.offline == true
                        Message(stringResource(if (offline) Res.string.image_offline else Res.string.image_missing))
                    },
                    success = { SubcomposeAsyncImageContent() },
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.scrim)
                .safeDrawingPadding(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(CittiIcon.Back, stringResource(Res.string.image_viewer_close), onClose)
            BasicText(
                name,
                Modifier.padding(end = CittiTheme.shape.gutter),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = CittiTheme.typography.rowTitle.colored(),
            )
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(NotesSpacing.space4), contentAlignment = Alignment.Center) {
        BasicText(text, style = CittiTheme.typography.body.colored(muted = true))
    }
}

private const val DECODE_FACTOR = 2
