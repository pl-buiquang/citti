package app.citti.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import app.citti.core.settings.MainMode
import app.citti.ui.resources.Res
import app.citti.ui.resources.mode_calendar
import app.citti.ui.resources.mode_list
import app.citti.ui.resources.mode_tree
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSize
import app.citti.ui.theme.NotesSpacing
import org.jetbrains.compose.resources.stringResource

/** The List / Tree / Calendar switch of the main view. The selected segment is filled with `accentSoft`. */
@Composable
fun ModeSwitch(
    mode: MainMode,
    onSelect: (MainMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(CittiTheme.shape.control)
    Row(
        modifier
            .selectableGroup()
            .clip(shape)
            .border(Dp.Hairline, CittiTheme.colors.line, shape),
    ) {
        Segment(stringResource(Res.string.mode_list), selected = mode == MainMode.LIST) { onSelect(MainMode.LIST) }
        Segment(stringResource(Res.string.mode_tree), selected = mode == MainMode.TREE) { onSelect(MainMode.TREE) }
        Segment(stringResource(Res.string.mode_calendar), selected = mode == MainMode.CALENDAR) { onSelect(MainMode.CALENDAR) }
    }
}

/** A row of segments where [selected] may be null (nothing chosen: the base's own value). Same look as [ModeSwitch]. */
@Composable
fun <T> SegmentedControl(
    options: List<Pair<String, T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(CittiTheme.shape.control)
    Row(
        modifier
            .selectableGroup()
            .clip(shape)
            .border(Dp.Hairline, CittiTheme.colors.line, shape),
    ) {
        for ((label, value) in options) Segment(label, selected = value == selected) { onSelect(value) }
    }
}

@Composable
private fun Segment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = CittiTheme.colors
    Box(
        Modifier
            .heightIn(min = NotesSize.touchMin)
            .background(if (selected) colors.accentSoft else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = NotesSpacing.space4),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            if (CittiTheme.shape.labelsUppercase) label.uppercase() else label,
            maxLines = 1,
            style = CittiTheme.typography.label.copy(color = if (selected) colors.accentInk else colors.inkMuted),
        )
    }
}
