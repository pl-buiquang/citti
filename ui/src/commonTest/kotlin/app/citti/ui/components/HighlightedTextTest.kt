package app.citti.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class HighlightedTextTest {
    private val hit = SpanStyle(color = Color.Red)

    private fun ranges(snippet: String) =
        highlightSnippet(snippet, hit).let { s ->
            s.text to
                s.spanStyles.map { s.text.substring(it.start, it.end) }
        }

    @Test
    fun markersBecomeSpansAndAreNotShown() {
        assertEquals("a sync b sync" to listOf("sync", "sync"), ranges("a \u0002sync\u0003 b \u0002sync\u0003"))
    }

    @Test
    fun noMarkersIsPlainText() {
        assertEquals("plain…" to emptyList(), ranges("plain…"))
    }

    @Test
    fun unbalancedMarkersAreTolerated() {
        // An end without a start is dropped.
        assertEquals("ab" to emptyList(), ranges("a\u0003b"))
        // A start without an end runs to the end.
        assertEquals("a bc" to listOf("bc"), ranges("a \u0002bc"))
        // A second start while open changes nothing.
        assertEquals("abc" to listOf("b", "c"), ranges("a\u0002b\u0002c\u0003"))
    }
}
