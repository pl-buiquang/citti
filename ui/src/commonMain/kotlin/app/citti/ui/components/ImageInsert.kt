package app.citti.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import app.citti.ui.TestTags
import app.citti.ui.image.PickSource
import app.citti.ui.resources.Res
import app.citti.ui.resources.image_camera
import app.citti.ui.resources.image_gallery
import app.citti.ui.resources.toolbar_image
import app.citti.ui.resources.upload_cancel
import app.citti.ui.resources.uploading_image
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSize
import app.citti.ui.theme.NotesSpacing
import org.jetbrains.compose.resources.stringResource

/** The sheet of Insert image: two rows, Camera and Gallery, at the bottom over a scrim; a tap outside dismisses it. */
@Composable
fun ImageSourceSheet(
    onPick: (PickSource) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CittiTheme.colors
    val shape = RoundedCornerShape(topStart = CittiTheme.shape.control, topEnd = CittiTheme.shape.control)
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.surfaceRaised, shape)
                .clickable(enabled = false) {}
                .padding(vertical = NotesSpacing.space2)
                .testTag(TestTags.IMAGE_SHEET),
        ) {
            BasicText(
                stringResource(Res.string.toolbar_image),
                Modifier.padding(horizontal = CittiTheme.shape.gutter, vertical = NotesSpacing.space2),
                style = CittiTheme.typography.meta.colored(muted = true),
            )
            SheetRow(stringResource(Res.string.image_camera), CittiIcon.Image, TestTags.IMAGE_CAMERA) { onPick(PickSource.Camera) }
            SheetRow(stringResource(Res.string.image_gallery), CittiIcon.Folder, TestTags.IMAGE_GALLERY) { onPick(PickSource.Gallery) }
        }
    }
}

@Composable
private fun SheetRow(
    label: String,
    icon: CittiIcon,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = NotesSize.touchMin)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = CittiTheme.shape.gutter)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space3),
    ) {
        Icon(icon, color = CittiTheme.colors.inkMuted)
        BasicText(label, style = CittiTheme.typography.rowTitle.colored())
    }
}

/** "Uploading image…" with a small progress ring and Cancel; it sits above the format toolbar. */
@Composable
fun UploadChip(
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CittiTheme.colors
    val label = stringResource(Res.string.uploading_image)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = CittiTheme.shape.gutter, vertical = NotesSpacing.space1)
            .background(colors.surfaceRaised, RoundedCornerShape(CittiTheme.shape.control))
            .padding(start = NotesSpacing.space3)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(TestTags.UPLOAD_CHIP),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space2),
    ) {
        ProgressRing(label)
        BasicText(label, Modifier.weight(1f), style = CittiTheme.typography.meta.colored(muted = true))
        Box(
            Modifier
                .heightIn(min = NotesSize.touchMin)
                .clickable(role = Role.Button, onClick = onCancel)
                .padding(horizontal = NotesSpacing.space3),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                stringResource(Res.string.upload_cancel),
                style = CittiTheme.typography.ui.copy(color = colors.accentInk),
            )
        }
    }
}

@Composable
private fun ProgressRing(label: String) {
    val color = CittiTheme.colors.accent
    val spin =
        rememberInfiniteTransition()
            .animateFloat(0f, 360f, infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing), RepeatMode.Restart))
            .value
    Canvas(
        Modifier
            .size(NotesSpacing.space4)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
    ) {
        val width = size.minDimension / 8f
        rotate(spin) {
            drawArc(
                color,
                startAngle = -90f,
                sweepAngle = 270f,
                useCenter = false,
                style = Stroke(width = width, cap = StrokeCap.Square),
                topLeft = Offset(width / 2, width / 2),
                size = Size(size.width - width, size.height - width),
            )
        }
    }
}
