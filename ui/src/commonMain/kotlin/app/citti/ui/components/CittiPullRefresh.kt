package app.citti.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import app.citti.ui.resources.Res
import app.citti.ui.resources.pull_refresh_action
import app.citti.ui.resources.sync_syncing
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSpacing
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max
import kotlin.math.roundToInt

/** How much of the finger's travel moves the indicator. */
private const val DRAG_RATE = 0.5f

/** The indicator stops following the finger at this many thresholds. */
private const val MAX_PULL = 2f

/**
 * Pull-to-refresh around a scrollable [content]. Pulling down past `space10` and releasing calls
 * [onRefresh]. The indicator stays up while [refreshing] is true and disappears when it turns false,
 * whether the sync succeeded or failed. TalkBack gets the same action as a custom action.
 */
@Composable
fun CittiPullRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val thresholdPx = with(LocalDensity.current) { NotesSpacing.space10.toPx() }
    val indicatorPx = with(LocalDensity.current) { NotesSpacing.space8.toPx() }
    var pull by remember { mutableFloatStateOf(0f) }
    val isRefreshing by rememberUpdatedState(refreshing)
    val refresh by rememberUpdatedState(onRefresh)
    val connection =
        remember(thresholdPx) {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    // Scrolling back up while pulled shrinks the pull before the list scrolls.
                    if (source != NestedScrollSource.UserInput || available.y >= 0f || pull <= 0f) return Offset.Zero
                    val before = pull
                    pull = (pull + available.y).coerceAtLeast(0f)
                    return Offset(0f, pull - before)
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    // The list is at its top and the finger keeps going down.
                    if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                    pull = (pull + available.y * DRAG_RATE).coerceAtMost(thresholdPx * MAX_PULL)
                    return Offset(0f, available.y)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (pull <= 0f) return Velocity.Zero
                    if (pull >= thresholdPx && !isRefreshing) refresh()
                    animate(pull, 0f) { value, _ -> pull = value }
                    return available
                }
            }
        }
    val actionLabel = stringResource(Res.string.pull_refresh_action)
    Box(
        modifier
            .nestedScroll(connection)
            .semantics {
                customActions =
                    listOf(
                        CustomAccessibilityAction(actionLabel) {
                            refresh()
                            true
                        },
                    )
            },
    ) {
        content()
        val shown = if (refreshing) max(pull, thresholdPx) else pull
        if (shown > 0f) {
            PullIndicator(
                refreshing = refreshing,
                progress = (pull / thresholdPx).coerceIn(0f, 1f),
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset { IntOffset(0, (shown - indicatorPx).roundToInt()) },
            )
        }
    }
}

@Composable
private fun PullIndicator(
    refreshing: Boolean,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val colors = CittiTheme.colors
    val label = stringResource(Res.string.sync_syncing)
    val spin =
        if (refreshing) {
            rememberInfiniteTransition()
                .animateFloat(0f, 360f, infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing), RepeatMode.Restart))
                .value
        } else {
            0f
        }
    Box(
        modifier
            .size(NotesSpacing.space8)
            .background(colors.surfaceRaised, CircleShape)
            .padding(NotesSpacing.space1)
            .then(
                if (refreshing) {
                    Modifier.semantics {
                        contentDescription = label
                        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(NotesSpacing.space6)) {
            val width = size.minDimension / 12f
            val sweep = if (refreshing) 270f else 300f * progress
            rotate(spin + 270f * progress) {
                drawArc(
                    colors.accent,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = Stroke(width = width, cap = StrokeCap.Square),
                    topLeft = Offset(width / 2, width / 2),
                    size = Size(size.width - width, size.height - width),
                )
            }
        }
    }
}
