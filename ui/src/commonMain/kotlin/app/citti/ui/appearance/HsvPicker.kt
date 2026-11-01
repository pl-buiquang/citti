package app.citti.ui.appearance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSpacing

/**
 * A hue / saturation / value picker drawn on a Canvas: a saturation × value square, a hue bar and, for tokens that
 * carry alpha, an alpha bar. [onChange] is called while dragging with the draft; [onCommit] when the finger lifts.
 */
@Composable
fun HsvPicker(
    hsv: Hsv,
    withAlpha: Boolean,
    onChange: (Hsv) -> Unit,
    onCommit: (Hsv) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(NotesSpacing.space3)) {
        Surface2D(hsv, onChange, onCommit)
        Bar(hsv.h / 360f, hueBrush(), hsvAt = { hsv.copy(h = it * 360f) }, onChange, onCommit)
        if (withAlpha) {
            val opaque = Color(hsvToArgb(hsv.copy(a = 1f)))
            Bar(hsv.a, Brush.horizontalGradient(listOf(opaque.copy(alpha = 0f), opaque)), hsvAt = { hsv.copy(a = it) }, onChange, onCommit)
        }
    }
}

private fun hueBrush() = Brush.horizontalGradient(listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { Color(hsvToArgb(Hsv(it, 1f, 1f))) })

@Composable
private fun Surface2D(
    hsv: Hsv,
    onChange: (Hsv) -> Unit,
    onCommit: (Hsv) -> Unit,
) {
    val line = CittiTheme.colors.line
    val current by rememberUpdatedState(hsv)
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(176.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()

                    fun at(p: Offset): Hsv =
                        current.copy(
                            s = (p.x / size.width).coerceIn(0f, 1f),
                            v = 1f - (p.y / size.height).coerceIn(0f, 1f),
                        )
                    var latest = at(down.position).also(change)
                    drag(down.id) { move ->
                        latest = at(move.position).also(change)
                        move.consume()
                    }
                    commit(latest)
                }
            },
    ) {
        drawRect(Color(hsvToArgb(Hsv(hsv.h, 1f, 1f))))
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        drawRect(line, style = Stroke(1f))
        val center = Offset(hsv.s * size.width, (1f - hsv.v) * size.height)
        drawCircle(Color.White, radius = 11.dp.toPx(), center = center, style = Stroke(3.dp.toPx()))
        drawCircle(Color.Black, radius = 11.dp.toPx() + 1.5.dp.toPx(), center = center, style = Stroke(1.dp.toPx()))
    }
}

@Composable
private fun Bar(
    fraction: Float,
    brush: Brush,
    hsvAt: (Float) -> Hsv,
    onChange: (Hsv) -> Unit,
    onCommit: (Hsv) -> Unit,
) {
    val line = CittiTheme.colors.line
    val at by rememberUpdatedState(hsvAt)
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()

                    fun x(p: Offset) = (p.x / size.width).coerceIn(0f, 1f)
                    var latest = at(x(down.position)).also(change)
                    drag(down.id) { move ->
                        latest = at(x(move.position)).also(change)
                        move.consume()
                    }
                    commit(latest)
                }
            },
    ) {
        drawRect(brush, size = Size(size.width, size.height))
        drawRect(line, style = Stroke(1f))
        val x = fraction * size.width
        drawRect(Color.White, topLeft = Offset(x - 2.dp.toPx(), 0f), size = Size(4.dp.toPx(), size.height))
        drawRect(Color.Black, topLeft = Offset(x - 2.dp.toPx(), 0f), size = Size(4.dp.toPx(), size.height), style = Stroke(1f))
    }
}
