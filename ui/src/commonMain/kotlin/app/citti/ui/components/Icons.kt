package app.citti.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSize
import app.citti.ui.theme.NotesSpacing
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Outline icons on a 24-unit grid with a 2-unit stroke (design-system README › Iconography), drawn
 * with Canvas so `:ui` needs no icon library.
 */
enum class CittiIcon {
    Menu,
    Back,
    Close,
    Search,
    Folder,
    Chevron,
    Star,
    Settings,
    Undo,
    Redo,
    Eye,
    Pencil,
    Image,
    Plus,
    More,
    Info,
    Trash,
    StarFilled,
    CalendarDay,
}

/** Decorative icon (no semantics): pair it with a label, or use [IconButton]. */
@Composable
fun Icon(
    icon: CittiIcon,
    modifier: Modifier = Modifier,
    color: Color = CittiTheme.colors.ink,
) {
    Canvas(modifier.size(NotesSpacing.space6)) { drawIcon(icon, color) }
}

/** An icon-only action: [NotesSize.iconButton] square, with [label] read by TalkBack. */
@Composable
fun IconButton(
    icon: CittiIcon,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = CittiTheme.colors.ink,
) {
    Box(
        modifier
            .sizeIn(minWidth = NotesSize.iconButton, minHeight = NotesSize.iconButton)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, color = color)
    }
}

private fun DrawScope.drawIcon(
    icon: CittiIcon,
    color: Color,
) {
    // One grid unit: the canvas is 24 units wide.
    val u = size.minDimension / 24f
    val stroke = Stroke(width = 2f * u, cap = StrokeCap.Square, join = StrokeJoin.Miter)

    fun p(
        x: Float,
        y: Float,
    ) = Offset(x * u, y * u)

    fun line(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ) = drawLine(color, p(x1, y1), p(x2, y2), strokeWidth = stroke.width, cap = stroke.cap)

    fun path(
        vararg points: Pair<Float, Float>,
        close: Boolean = false,
        fill: Boolean = false,
    ) {
        val path =
            Path().apply {
                points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * u, y * u) else lineTo(x * u, y * u) }
                if (close) close()
            }
        if (fill) drawPath(path, color) else drawPath(path, color, style = stroke)
    }

    when (icon) {
        CittiIcon.Menu -> {
            line(4f, 7f, 20f, 7f)
            line(4f, 12f, 20f, 12f)
            line(4f, 17f, 20f, 17f)
        }

        CittiIcon.Back -> {
            line(5f, 12f, 19f, 12f)
            path(11f to 6f, 5f to 12f, 11f to 18f)
        }

        CittiIcon.Close -> {
            line(6f, 6f, 18f, 18f)
            line(18f, 6f, 6f, 18f)
        }

        CittiIcon.Search -> {
            drawCircle(color, radius = 6f * u, center = p(10.5f, 10.5f), style = stroke)
            line(15f, 15f, 20f, 20f)
        }

        CittiIcon.Folder -> {
            path(3f to 6f, 9f to 6f, 11f to 8f, 21f to 8f, 21f to 19f, 3f to 19f, close = true)
        }

        CittiIcon.Chevron -> {
            path(9f to 6f, 15f to 12f, 9f to 18f)
        }

        CittiIcon.Settings -> {
            // A gear: a hub, a ring, and eight teeth.
            drawCircle(color, radius = 3f * u, center = p(12f, 12f), style = stroke)
            drawCircle(color, radius = 6.5f * u, center = p(12f, 12f), style = stroke)
            for (i in 0 until 8) {
                val a = i * PI.toFloat() / 4f
                line(12f + 6.5f * cos(a), 12f + 6.5f * sin(a), 12f + 9.5f * cos(a), 12f + 9.5f * sin(a))
            }
        }

        CittiIcon.Undo -> {
            path(9f to 5f, 4f to 10f, 9f to 15f)
            path(4f to 10f, 14f to 10f, 20f to 14f, 20f to 19f)
        }

        CittiIcon.Redo -> {
            path(15f to 5f, 20f to 10f, 15f to 15f)
            path(20f to 10f, 10f to 10f, 4f to 14f, 4f to 19f)
        }

        CittiIcon.Eye -> {
            path(2f to 12f, 7f to 6.5f, 12f to 5f, 17f to 6.5f, 22f to 12f, 17f to 17.5f, 12f to 19f, 7f to 17.5f, close = true)
            drawCircle(color, radius = 3f * u, center = p(12f, 12f), style = stroke)
        }

        CittiIcon.Pencil -> {
            path(4f to 20f, 5f to 15f, 16f to 4f, 20f to 8f, 9f to 19f, close = true)
            line(13f, 7f, 17f, 11f)
        }

        CittiIcon.Image -> {
            path(3f to 5f, 21f to 5f, 21f to 19f, 3f to 19f, close = true)
            drawCircle(color, radius = 1.5f * u, center = p(8.5f, 10f), style = stroke)
            path(3f to 17f, 9f to 12f, 13f to 16f, 16f to 13f, 21f to 17f)
        }

        CittiIcon.Plus -> {
            line(12f, 5f, 12f, 19f)
            line(5f, 12f, 19f, 12f)
        }

        CittiIcon.Star, CittiIcon.StarFilled -> {
            path(
                12f to 3.5f,
                14.6f to 9f,
                20.5f to 9.6f,
                16f to 13.5f,
                17.3f to 19.5f,
                12f to 16.4f,
                6.7f to 19.5f,
                8f to 13.5f,
                3.5f to 9.6f,
                9.4f to 9f,
                close = true,
                fill = icon == CittiIcon.StarFilled,
            )
        }

        CittiIcon.More -> {
            // ⋮: three dots.
            for (y in listOf(5f, 12f, 19f)) drawCircle(color, radius = 1.5f * u, center = p(12f, y))
        }

        CittiIcon.Info -> {
            drawCircle(color, radius = 9f * u, center = p(12f, 12f), style = stroke)
            line(12f, 11f, 12f, 17f)
            drawCircle(color, radius = 1.2f * u, center = p(12f, 7.5f))
        }

        CittiIcon.Trash -> {
            line(4f, 6f, 20f, 6f)
            path(9f to 6f, 9f to 3.5f, 15f to 3.5f, 15f to 6f)
            path(6f to 6f, 7f to 20.5f, 17f to 20.5f, 18f to 6f)
            line(10f, 10f, 10f, 17f)
            line(14f, 10f, 14f, 17f)
        }

        CittiIcon.CalendarDay -> {
            // A calendar page: the binding rings, the header rule, and one day filled in.
            path(4f to 5f, 20f to 5f, 20f to 20f, 4f to 20f, close = true)
            line(4f, 10f, 20f, 10f)
            line(8f, 3f, 8f, 6f)
            line(16f, 3f, 16f, 6f)
            path(10f to 13f, 14f to 13f, 14f to 17f, 10f to 17f, close = true, fill = true)
        }
    }
}
