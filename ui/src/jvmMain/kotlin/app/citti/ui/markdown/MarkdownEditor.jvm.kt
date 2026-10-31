package app.citti.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** The JVM target only runs the shared logic in tests; there is no editor view. */
@Composable
actual fun MarkdownEditor(
    state: EditorState,
    modifier: Modifier,
    editable: Boolean,
) = Unit
