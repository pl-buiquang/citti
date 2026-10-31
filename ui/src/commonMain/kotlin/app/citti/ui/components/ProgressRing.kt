package app.citti.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSpacing

/** A small indeterminate ring, for inline progress on a button while a server check runs. */
@Composable
fun ProgressRing(
    modifier: Modifier = Modifier,
    color: Color = CittiTheme.colors.accent,
) {
    val spin by rememberInfiniteTransition()
        .animateFloat(0f, 360f, infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing), RepeatMode.Restart))
    Canvas(
        modifier
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
