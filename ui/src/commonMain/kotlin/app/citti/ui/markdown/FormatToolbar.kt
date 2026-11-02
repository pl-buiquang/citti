package app.citti.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import app.citti.core.markdown.ToolbarAction
import app.citti.ui.TestTags
import app.citti.ui.resources.Res
import app.citti.ui.resources.toolbar_bold
import app.citti.ui.resources.toolbar_checkbox
import app.citti.ui.resources.toolbar_heading
import app.citti.ui.resources.toolbar_image
import app.citti.ui.resources.toolbar_italic
import app.citti.ui.resources.toolbar_link
import app.citti.ui.resources.toolbar_list
import app.citti.ui.resources.toolbar_strike
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSize
import org.jetbrains.compose.resources.stringResource

/**
 * The formatting buttons above the keyboard (design-system `FormatToolbar.md`), then Insert image. Each shows the
 * markdown mark it inserts in the editor face, so the same toolbar serves every theme. A button is pressed (accent fill) while its
 * syntax is active at the caret. Show it only while the editor has focus in Edit mode, above the IME.
 */
@Composable
fun FormatToolbar(
    buttons: List<ToolbarAction>,
    pressed: Set<ToolbarAction>,
    onAction: (ToolbarAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CittiTheme.colors
    Column(modifier.fillMaxWidth().background(colors.surface)) {
        val line = colors.line
        Spacer(
            Modifier.fillMaxWidth().drawBehind { drawLine(line, Offset.Zero, Offset(size.width, 0f), strokeWidth = Stroke.HairlineWidth) },
        )
        // Spread across the width; scrolls sideways when the buttons need more than the screen has.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).widthIn(min = maxWidth),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                buttons.forEach { action ->
                    val on = action in pressed
                    val label = stringResource(action.label())
                    Box(
                        Modifier
                            .sizeIn(minWidth = NotesSize.touchMin, minHeight = NotesSize.touchMin)
                            .background(if (on) colors.accent else colors.surface)
                            .clickable(role = Role.Button) { onAction(action) }
                            .testTag(if (action == ToolbarAction.Image) TestTags.TOOLBAR_IMAGE else TestTags.toolbar(action))
                            .semantics {
                                contentDescription = label
                                role = Role.Button
                                selected = on
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            action.mark(),
                            style =
                                CittiTheme.typography.editor.copy(
                                    color = if (on) colors.onAccent else colors.ink,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                        )
                    }
                }
            }
        }
    }
}

internal fun ToolbarAction.mark() =
    when (this) {
        ToolbarAction.Bold -> "**"
        ToolbarAction.Italic -> "_"
        ToolbarAction.Strike -> "~~"
        ToolbarAction.Heading -> "#"
        ToolbarAction.List -> "-"
        ToolbarAction.Checkbox -> "[ ]"
        ToolbarAction.Link -> "[["
        ToolbarAction.Image -> "![]"
    }

internal fun ToolbarAction.label() =
    when (this) {
        ToolbarAction.Bold -> Res.string.toolbar_bold
        ToolbarAction.Italic -> Res.string.toolbar_italic
        ToolbarAction.Strike -> Res.string.toolbar_strike
        ToolbarAction.Heading -> Res.string.toolbar_heading
        ToolbarAction.List -> Res.string.toolbar_list
        ToolbarAction.Checkbox -> Res.string.toolbar_checkbox
        ToolbarAction.Link -> Res.string.toolbar_link
        ToolbarAction.Image -> Res.string.toolbar_image
    }
