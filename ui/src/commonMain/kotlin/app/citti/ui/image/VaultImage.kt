package app.citti.ui.image

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.citti.core.markdown.Inline
import app.citti.ui.components.CittiIcon
import app.citti.ui.components.Icon
import app.citti.ui.components.colored
import app.citti.ui.resources.Res
import app.citti.ui.resources.image_missing
import app.citti.ui.resources.image_offline
import app.citti.ui.resources.image_open
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSize
import app.citti.ui.theme.NotesSpacing
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.request.ImageRequest
import org.jetbrains.compose.resources.stringResource

/**
 * An Image of a Note in Preview mode: loaded by Coil through the Vault fetcher, drawn at the Note's width (`|900`
 * is a maximum width in dp, `|900x300` sets the height too), with the theme's media corner. Loading shows a raised
 * box (4:3 until the size is known); a failure shows a bordered box with the file name and why. A tap opens the
 * Image viewer.
 */
@Composable
fun VaultImage(
    image: Inline.Image,
    env: ImageEnv,
    modifier: Modifier = Modifier,
) {
    val name = imageFileName(image.src)
    val description = image.alt.ifEmpty { name }
    val shape = RoundedCornerShape(CittiTheme.shape.media)
    val model = env.model(image.src)
    val width = image.width?.dp
    val sized =
        modifier
            .padding(vertical = NotesSpacing.space2)
            .then(if (width != null) Modifier.widthIn(max = width) else Modifier)
            .fillMaxWidth()
    if (model == null) {
        UnavailableBox(name, image.alt, ImageUnavailable.Reason.Unsupported, sized, shape)
        return
    }
    val request = ImageRequest.Builder(LocalPlatformContext.current).data(model).build()
    SubcomposeAsyncImage(
        model = request,
        contentDescription = description,
        imageLoader = env.loader,
        modifier = sized.clip(shape),
        contentScale = ContentScale.FillWidth,
        loading = { Box(Modifier.fillMaxWidth().aspectRatio(PLACEHOLDER_ASPECT).background(CittiTheme.colors.surfaceRaised)) },
        error = { state ->
            val reason = (state.result.throwable as? ImageUnavailable)?.reason ?: ImageUnavailable.Reason.Failed
            UnavailableBox(name, image.alt, reason, Modifier.fillMaxWidth(), shape)
        },
        success = {
            SubcomposeAsyncImageContent(
                Modifier
                    .then(if (image.height != null) Modifier.height(image.height!!.dp) else Modifier)
                    .semantics { contentDescription = description }
                    .clickable(onClickLabel = stringResource(Res.string.image_open), role = Role.Button) { env.onOpen(image.src) },
            )
        },
    )
}

@Composable
private fun UnavailableBox(
    name: String,
    alt: String,
    reason: ImageUnavailable.Reason,
    modifier: Modifier,
    shape: RoundedCornerShape,
) {
    val why =
        stringResource(
            when (reason) {
                ImageUnavailable.Reason.Missing, ImageUnavailable.Reason.Unsupported -> Res.string.image_missing
                else -> Res.string.image_offline
            },
        )
    Row(
        modifier
            .heightIn(min = NotesSize.touchMin)
            .border(Dp.Hairline, CittiTheme.colors.line, shape)
            .padding(NotesSpacing.space3)
            .semantics { contentDescription = alt.ifEmpty { name } + ", " + why },
        horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CittiIcon.Image, color = CittiTheme.colors.inkMuted)
        Column {
            BasicText(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = CittiTheme.typography.meta.colored(muted = true))
            BasicText(why, maxLines = 1, overflow = TextOverflow.Ellipsis, style = CittiTheme.typography.meta.colored(muted = true))
        }
    }
}

private const val PLACEHOLDER_ASPECT = 4f / 3f
