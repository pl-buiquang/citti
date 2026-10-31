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
    val editorStyle = CittiTheme.typography.editor
    val density = LocalDensity.current
    val resolver = LocalFontFamilyResolver.current
    val face: Typeface =
        remember(editorStyle.fontFamily, resolver) {
            resolver
                .resolve(editorStyle.fontFamily ?: FontFamily.Default, FontWeight.Normal, FontStyle.Normal, FontSynthesis.All)
                .value as? Typeface ?: Typeface.MONOSPACE
        }
    val styles =
        SyntaxStyles(
            ink = colors.ink.toArgb(),
            inkMuted = colors.inkMuted.toArgb(),
            inkFaint = colors.inkFaint.toArgb(),
            accentInk = colors.accentInk.toArgb(),
            highlight = colors.highlight.toArgb(),
            mono = Typeface.MONOSPACE,
        )
    val padding = with(density) { NotesSpacing.space4.roundToPx() }
    val currentStyles by rememberUpdatedState(styles)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            CittiEditText(context, state, currentStyles).also { view ->
                view.typeface = face
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, editorStyle.fontSize.value)
                view.lineHeight = with(density) { editorStyle.lineHeight.toPx().toInt() }
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
            if (view.styles != styles) {
                view.styles = styles
                view.restyle(0 until state.document.lineCount)
            }
        },
        onRelease = { view -> state.detach(view) },
    )
}
