package app.citti.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import app.citti.core.markdown.ToolbarAction
import app.citti.ui.resources.Res
import app.citti.ui.resources.toolbar_bold
import app.citti.ui.resources.toolbar_checkbox
import app.citti.ui.resources.toolbar_heading
import app.citti.ui.resources.toolbar_italic
import app.citti.ui.resources.toolbar_link
import app.citti.ui.resources.toolbar_list
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSize
import org.jetbrains.compose.resources.stringResource

/**
 * The six formatting buttons above the keyboard (design-system `FormatToolbar.md`). Each shows the markdown mark it
 * inserts in the editor face, so the same toolbar serves every theme. A button is pressed (accent fill) while its
 * syntax is active at the caret. Show it only while the editor has focus in Edit mode, above the IME.
 */
@Composable
fun FormatToolbar(
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ToolbarAction.entries.forEach { action ->
                val on = action in pressed
                val label = stringResource(action.label())
                Box(
                    Modifier
                        .sizeIn(minWidth = NotesSize.touchMin, minHeight = NotesSize.touchMin)
                        .background(if (on) colors.accent else colors.surface)
                        .clickable(role = Role.Button) { onAction(action) }
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

private fun ToolbarAction.mark() =
    when (this) {
        ToolbarAction.Bold -> "**"
        ToolbarAction.Italic -> "_"
        ToolbarAction.Heading -> "#"
        ToolbarAction.List -> "-"
        ToolbarAction.Checkbox -> "[ ]"
        ToolbarAction.Link -> "[["
    }

private fun ToolbarAction.label() =
    when (this) {
        ToolbarAction.Bold -> Res.string.toolbar_bold
        ToolbarAction.Italic -> Res.string.toolbar_italic
        ToolbarAction.Heading -> Res.string.toolbar_heading
        ToolbarAction.List -> Res.string.toolbar_list
        ToolbarAction.Checkbox -> Res.string.toolbar_checkbox
        ToolbarAction.Link -> Res.string.toolbar_link
    }
