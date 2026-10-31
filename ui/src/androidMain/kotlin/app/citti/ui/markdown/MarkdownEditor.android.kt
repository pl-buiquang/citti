package app.citti.ui.markdown

import android.graphics.Typeface
import android.util.TypedValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSpacing

@Composable
actual fun MarkdownEditor(
    state: EditorState,
    modifier: Modifier,
    editable: Boolean,
) {
    val colors = CittiTheme.colors
    val bodyStyle = CittiTheme.typography.body
    val markStyle = CittiTheme.typography.editor
    val density = LocalDensity.current
    val resolver = LocalFontFamilyResolver.current
    val face: Typeface =
        remember(bodyStyle.fontFamily, resolver) {
            resolver
                .resolve(bodyStyle.fontFamily ?: FontFamily.Default, FontWeight.Normal, FontStyle.Normal, FontSynthesis.All)
                .value as? Typeface ?: Typeface.DEFAULT
        }
    val markFace: Typeface =
        remember(markStyle.fontFamily, resolver) {
            resolver
                .resolve(markStyle.fontFamily ?: FontFamily.Default, FontWeight.Normal, FontStyle.Normal, FontSynthesis.All)
                .value as? Typeface ?: Typeface.MONOSPACE
        }
    val styles =
        SyntaxStyles(
            ink = colors.ink.toArgb(),
            inkMuted = colors.inkMuted.toArgb(),
            inkFaint = colors.inkFaint.toArgb(),
            accentInk = colors.accentInk.toArgb(),
            highlight = colors.highlight.toArgb(),
            mono = markFace,
        )
    val textSize = editorTextSize(bodyStyle, density)
    val padding = with(density) { NotesSpacing.space4.roundToPx() }
    val currentStyles by rememberUpdatedState(styles)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            CittiEditText(context, state, currentStyles).also { view ->
                view.typeface = face
                view.setPadding(padding, padding, padding, padding)
                state.attach(view)
            }
        },
        update = { view ->
            view.isEnabled = editable
            view.setTextColor(colors.ink.toArgb())
            view.setHintTextColor(colors.inkFaint.toArgb())
            view.highlightColor = colors.accentSoft.toArgb()
            view.typeface = face
            // Through the Compose density, not COMPLEX_UNIT_SP: that one ignores the Text size of the Appearance.
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize.textPx)
            view.lineHeight = textSize.lineHeightPx
            if (view.styles != styles) {
                view.styles = styles
                view.restyle(0 until state.document.lineCount)
            }
        },
        onRelease = { view -> state.detach(view) },
    )
}
