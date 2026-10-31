package app.citti.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import app.citti.ui.theme.CittiTheme

/** Start and end of a search hit in a `snippet()` result (`NoteSearch`). */
const val HIT_START = '\u0002'
const val HIT_END = '\u0003'

/**
 * Turns a snippet with [HIT_START] / [HIT_END] markers into styled text. The markers are never shown.
 * An end without a start is dropped; a start without an end highlights to the end of the snippet.
 */
fun highlightSnippet(
    snippet: String,
    hit: SpanStyle,
): AnnotatedString =
    buildAnnotatedString {
        var open = false
        val run = StringBuilder()

        fun flush() {
            if (run.isEmpty()) return
            if (open) withStyle(hit) { append(run) } else append(run)
            run.clear()
        }
        for (c in snippet) {
            when (c) {
                HIT_START -> {
                    flush()
                    open = true
                }

                HIT_END -> {
                    flush()
                    open = false
                }

                else -> {
                    run.append(c)
                }
            }
        }
        flush()
    }

/** [highlightSnippet] with the theme's `highlight` / `onHighlight` colors. */
@Composable
fun rememberHighlighted(snippet: String): AnnotatedString {
    val colors = CittiTheme.colors
    return remember(snippet, colors.highlight, colors.onHighlight) {
        highlightSnippet(snippet, SpanStyle(color = colors.onHighlight, background = colors.highlight))
    }
}
