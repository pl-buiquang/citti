package app.citti.ui.create

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import app.citti.ui.TestTags
import app.citti.ui.components.CittiIcon
import app.citti.ui.components.Icon
import app.citti.ui.components.ProgressRing
import app.citti.ui.resources.Res
import app.citti.ui.resources.new_note
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSize
import app.citti.ui.theme.NotesSpacing
import app.citti.ui.theme.ThemeFamily
import org.jetbrains.compose.resources.stringResource

/**
 * The New note button (design-system `NewNoteButton.md`), per theme family: Signal a 64 dp `accent` disc (the
 * caller floats it), Paper a 48 dp `ink` disc and Index a labelled `accent` button (both at the end of the bottom
 * bar). Content description "New note". [busy] shows an inline progress ring while the server check runs.
 */
@Composable
fun NewNoteButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
) {
    val colors = CittiTheme.colors
    val label = stringResource(Res.string.new_note)
    val shape = RoundedCornerShape(CittiTheme.shape.newNote)
    val base =
        modifier
            .testTag(TestTags.NEW_NOTE_BUTTON)
            .semantics(mergeDescendants = true) { contentDescription = label }
    when (CittiTheme.family) {
        ThemeFamily.Signal -> {
            Disc(base, NotesSize.fabSignal, colors.accent, colors.onAccent, busy, onClick)
        }

        ThemeFamily.Paper -> {
            Disc(base, NotesSize.iconButton, colors.ink, colors.bg, busy, onClick)
        }

        ThemeFamily.Index -> {
            Row(
                base
                    .heightIn(min = NotesSize.touchMin)
                    .background(colors.accent, shape)
                    .clickable(role = Role.Button, enabled = !busy, onClick = onClick)
                    .padding(horizontal = NotesSpacing.space3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space2),
            ) {
                if (busy) ProgressRing(color = colors.onAccent)
                val text = if (CittiTheme.shape.labelsUppercase) label.uppercase() else label
                BasicText(text, style = CittiTheme.typography.ui.copy(color = colors.onAccent))
            }
        }
    }
}

@Composable
private fun Disc(
    modifier: Modifier,
    size: Dp,
    fill: Color,
    content: Color,
    busy: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .background(fill, RoundedCornerShape(CittiTheme.shape.newNote))
            .clickable(role = Role.Button, enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) ProgressRing(color = content) else Icon(CittiIcon.Plus, color = content)
    }
}
