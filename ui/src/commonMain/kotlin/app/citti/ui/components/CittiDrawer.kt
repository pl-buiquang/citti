package app.citti.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import app.citti.ui.resources.Res
import app.citti.ui.resources.drawer_close
import app.citti.ui.resources.drawer_title
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSize
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

enum class DrawerValue { Closed, Open }

/**
 * Open / closed state of a [CittiDrawer]. Back is not handled here (`:ui` commonMain has no back
 * dispatcher): the host's back handler checks [isOpen] and calls [close] before it pops a route.
 */
@Stable
class CittiDrawerState(
    initial: DrawerValue,
) {
    internal val anchored = AnchoredDraggableState(initial)

    /** True while the drawer is open or opening. */
    val isOpen: Boolean get() = anchored.targetValue == DrawerValue.Open

    suspend fun open() = anchored.animateTo(DrawerValue.Open)

    suspend fun close() = anchored.animateTo(DrawerValue.Closed)

    companion object {
        val Saver: Saver<CittiDrawerState, Boolean> =
            Saver(
                save = { it.anchored.settledValue == DrawerValue.Open },
                restore = { CittiDrawerState(if (it) DrawerValue.Open else DrawerValue.Closed) },
            )
    }
}

@Composable
fun rememberCittiDrawerState(initial: DrawerValue = DrawerValue.Closed): CittiDrawerState =
    rememberSaveable(saver = CittiDrawerState.Saver) { CittiDrawerState(initial) }

/**
 * A modal drawer from the start edge, [NotesSize.drawerWidth] wide, over a `scrim`. Swipe towards
 * the end to open; swipe it back or tap the scrim to close.
 */
@Composable
fun CittiDrawer(
    state: CittiDrawerState,
    drawerContent: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    gesturesEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = CittiTheme.colors
    val scope = rememberCoroutineScope()
    val widthPx = with(LocalDensity.current) { NotesSize.drawerWidth.toPx() }
    SideEffect {
        state.anchored.updateAnchors(
            DraggableAnchors {
                DrawerValue.Closed at -widthPx
                DrawerValue.Open at 0f
            },
        )
    }
    // 0 when closed, 1 when open. The offset is NaN until the anchors are set.
    val fraction by remember(state, widthPx) {
        derivedStateOf {
            val offset = state.anchored.offset
            if (offset.isNaN()) 0f else ((offset + widthPx) / widthPx).coerceIn(0f, 1f)
        }
    }
    // Its own derived state, so a drag or an animation does not recompose the drawer every frame.
    val shown by remember(state) { derivedStateOf { fraction > 0f || state.isOpen } }
    val closeLabel = stringResource(Res.string.drawer_close)
    val title = stringResource(Res.string.drawer_title)
    val close: () -> Unit = { scope.launch { state.close() } }

    // The drag is on the whole surface (as in Material's modal drawer): a strip at the start edge
    // would eat taps there and fight the system back gesture.
    Box(
        modifier
            .fillMaxSize()
            .anchoredDraggable(state.anchored, Orientation.Horizontal, enabled = gesturesEnabled),
    ) {
        content()
        if (shown) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = fraction }
                    .background(colors.scrim)
                    .clickable(interactionSource = null, indication = null, onClick = close)
                    .semantics {
                        contentDescription = closeLabel
                        onClick(closeLabel) {
                            close()
                            true
                        }
                    },
            )
            Column(
                Modifier
                    .fillMaxHeight()
                    .width(NotesSize.drawerWidth)
                    .offset {
                        val offset = state.anchored.offset
                        IntOffset(if (offset.isNaN()) -widthPx.roundToInt() else offset.roundToInt(), 0)
                    }.background(colors.surfaceRaised)
                    // Taps on an empty part of the panel must not reach the scrim below.
                    .pointerInput(Unit) { detectTapGestures {} }
                    .semantics {
                        paneTitle = title
                        dismiss(closeLabel) {
                            close()
                            true
                        }
                        customActions =
                            listOf(
                                CustomAccessibilityAction(closeLabel) {
                                    close()
                                    true
                                },
                            )
                    },
                content = drawerContent,
            )
        }
    }
}
