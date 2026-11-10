package app.citti.ui.markdown

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Set by content that scrolls sideways (a code block, a table) when a gesture starts on it, so the Note screen's swipe
 * to the next Note leaves that gesture alone. The screen clears it at each new gesture, before the content sees it.
 */
class SwipeGuard {
    var blocked: Boolean = false
}

val LocalSwipeGuard = compositionLocalOf<SwipeGuard?> { null }

/** Marks gestures that start on this content as its own while [scroll] can move. Consumes nothing. */
internal fun Modifier.blocksSwipe(scroll: ScrollState): Modifier =
    composed {
        val guard = LocalSwipeGuard.current
        pointerInput(guard, scroll) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (scroll.maxValue > 0) guard?.blocked = true
            }
        }
    }
