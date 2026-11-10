package app.citti.ui.note

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlin.math.abs

/** A horizontal move of at least this share of the width, in under [SWIPE_MAX_MS], mostly horizontal, is a swipe. */
private const val SWIPE_WIDTH_SHARE = 0.25f
private const val SWIPE_MAX_MS = 500L
private const val SWIPE_DOMINANCE = 2f

/** What a finished one-finger gesture is: [dx], [dy] in pixels over [ms] on a [width] wide area. */
internal fun swipeDirection(
    dx: Float,
    dy: Float,
    ms: Long,
    width: Int,
): SwipeDirection? =
    when {
        ms > SWIPE_MAX_MS || width <= 0 -> null
        abs(dx) < width * SWIPE_WIDTH_SHARE || abs(dx) < abs(dy) * SWIPE_DOMINANCE -> null
        dx < 0 -> SwipeDirection.Next
        else -> SwipeDirection.Previous
    }

internal enum class SwipeDirection { Previous, Next }

/**
 * Swipe left for the next Note, right for the previous one. Watches the pointers without consuming anything. In the
 * Preview ([childrenFirst]) it looks at the events after the content, so a code block or a table that scrolls
 * sideways keeps its drag; the editor (a native view that consumes every touch) is watched before it.
 */
internal fun Modifier.swipeToNeighbor(
    nav: NoteNav?,
    childrenFirst: Boolean,
    onOpen: (NeighborLink) -> Unit,
): Modifier =
    composed {
        val current by rememberUpdatedState(nav)
        val open by rememberUpdatedState(onOpen)
        val pass = if (childrenFirst) PointerEventPass.Final else PointerEventPass.Initial
        val slop = LocalViewConfiguration.current.touchSlop
        pointerInput(pass, slop) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
                var last = down.position
                var fingers = 1
                var broken = false
                while (true) {
                    val event = awaitPointerEvent(pass)
                    fingers = maxOf(fingers, event.changes.size)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (childrenFirst && change.isConsumed && change.pressed) broken = true
                    last = change.position
                    if (!change.pressed) {
                        val delta: Offset = last - down.position
                        val direction = swipeDirection(delta.x, delta.y, change.uptimeMillis - down.uptimeMillis, size.width)
                        val link =
                            when (direction) {
                                SwipeDirection.Next -> current?.next
                                SwipeDirection.Previous -> current?.previous
                                null -> null
                            }
                        if (link != null && fingers == 1 && !broken) open(link)
                        break
                    }
                }
            }
        }
    }
