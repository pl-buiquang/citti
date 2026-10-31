package app.citti.ui.markdown

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditorTextSizeTest {
    private val style = TextStyle(fontSize = 17.sp, lineHeight = 27.sp)

    @Test
    fun followsTheScaledDensity() {
        val plain = editorTextSize(style, Density(2f, 1f))
        assertEquals(34f, plain.textPx)
        assertEquals(54, plain.lineHeightPx)
        val scaled = editorTextSize(style, Density(2f, 1.3f))
        // Compose scales sp non-linearly above 1.05 (as the OS does), so only check that it grows.
        assertTrue(scaled.textPx > plain.textPx)
        assertTrue(scaled.lineHeightPx > plain.lineHeightPx)
    }
}
