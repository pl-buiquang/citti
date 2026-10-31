package app.citti.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Edit mode: the platform text view, styled by the line lexer. [state] outlives the composition; the view attaches
 * to it on entering and detaches on leaving. Android: an `EditText` (ADR 0009).
 */
@Composable
expect fun MarkdownEditor(
    state: EditorState,
    modifier: Modifier = Modifier,
    editable: Boolean = true,
)
