package app.citti.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import app.citti.ui.resources.Res
import app.citti.ui.resources.today_label
import app.citti.ui.resources.today_note
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSize
import app.citti.ui.theme.NotesSpacing
import app.citti.ui.theme.ThemeFamily
import org.jetbrains.compose.resources.stringResource

/** Signal's Today disc is 40 dp (design-system `TodayButton.md`); the scale has no size token for it. */
private val SignalDisc = NotesSpacing.space10

/**
 * The Today button (design-system `TodayButton.md`), the secondary action left of [app.citti.ui.create.NewNoteButton],
 * per theme family: Signal a 40 dp `surfaceRaised` disc with an `ink` calendar-day icon (the caller floats it on the
 * New note button's baseline, `space-3` to its left), Paper an icon button in the bottom bar, Index a labelled "Today"
 * button in the bottom status bar. Never the primary look. Content description "Today's note". [busy] (today's note
 * is being opened) replaces the icon with an inline progress ring and ignores taps.
 */
@Composable
fun TodayButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
) {
    val colors = CittiTheme.colors
    val description = stringResource(Res.string.today_note)
    val shape = RoundedCornerShape(CittiTheme.shape.newNote)
    val base = modifier.semantics(mergeDescendants = true) { contentDescription = description }
    when (CittiTheme.family) {
        ThemeFamily.Signal -> {
            // The disc is under touchMin: the tap target around it is touchMin, the same shape.
            Box(
                base
                    .size(NotesSize.touchMin)
                    .clip(shape)
                    .clickable(role = Role.Button, enabled = !busy, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(SignalDisc)
                        .background(colors.surfaceRaised, shape)
                        .border(Dp.Hairline, colors.line, shape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) ProgressRing(color = colors.ink) else Icon(CittiIcon.CalendarDay, color = colors.ink)
                }
            }
        }

        ThemeFamily.Paper -> {
            Box(
                base
                    .size(NotesSize.iconButton)
                    .clip(shape)
                    .clickable(role = Role.Button, enabled = !busy, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) ProgressRing(color = colors.ink) else Icon(CittiIcon.CalendarDay, color = colors.ink)
            }
        }

        ThemeFamily.Index -> {
            val label = stringResource(Res.string.today_label)
            Row(
                base
                    .heightIn(min = NotesSize.touchMin)
                    .clip(shape)
                    .border(Dp.Hairline, colors.line, shape)
                    .clickable(role = Role.Button, enabled = !busy, onClick = onClick)
                    .padding(horizontal = NotesSpacing.space3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space2),
            ) {
                if (busy) ProgressRing(color = colors.ink)
                BasicText(
                    if (CittiTheme.shape.labelsUppercase) label.uppercase() else label,
                    style = CittiTheme.typography.ui.copy(color = colors.ink),
                )
            }
        }
    }
}
