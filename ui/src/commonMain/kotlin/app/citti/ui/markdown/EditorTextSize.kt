package app.citti.ui.markdown

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density

/** The Edit mode text size and line height in pixels, through the Compose [Density] so that the Text size applies. */
data class EditorTextSize(
    val textPx: Float,
    val lineHeightPx: Int,
)

fun editorTextSize(
    style: TextStyle,
    density: Density,
): EditorTextSize = with(density) { EditorTextSize(style.fontSize.toPx(), style.lineHeight.toPx().toInt()) }
