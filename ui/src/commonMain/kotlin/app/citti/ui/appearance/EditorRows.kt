package app.citti.ui.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import app.citti.ui.components.CittiIcon
import app.citti.ui.components.Icon
import app.citti.ui.components.IconButton
import app.citti.ui.components.colored
import app.citti.ui.resources.Res
import app.citti.ui.resources.theme_editor_reset_row
import app.citti.ui.resources.theme_editor_text_size_less
import app.citti.ui.resources.theme_editor_text_size_more
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSize
import app.citti.ui.theme.NotesSpacing
import org.jetbrains.compose.resources.stringResource

/** The reset icon of an overridden row. */
@Composable
internal fun ResetButton(
    rowName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        CittiIcon.Reset,
        stringResource(Res.string.theme_editor_reset_row, rowName),
        onClick,
        modifier,
        color = CittiTheme.colors.inkMuted,
    )
}

/** A row with a name and a value, tappable, with the reset icon while [overridden]. */
@Composable
internal fun ValueRow(
    name: String,
    value: String,
    overridden: Boolean,
    onClick: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = {},
    detail: String? = null,
    valueFont: FontFamily? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = NotesSize.touchMin).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space3),
    ) {
        leading()
        Column(Modifier.weight(1f)) {
            BasicText(name, style = CittiTheme.typography.body.colored())
            val style = CittiTheme.typography.meta.colored(muted = true)
            BasicText(value, style = if (valueFont != null) style.copy(fontFamily = valueFont) else style)
            if (detail != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space1)) {
                    Icon(CittiIcon.Info, Modifier.size(NotesSpacing.space4), color = CittiTheme.colors.danger)
                    BasicText(detail, style = CittiTheme.typography.meta.copy(color = CittiTheme.colors.danger))
                }
            }
        }
        if (overridden) ResetButton(name, onReset)
    }
}

/** A color swatch with a hairline so a color equal to the page background is still visible. */
@Composable
internal fun Swatch(
    argb: Long,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(CittiTheme.shape.control / 2)
    Box(
        modifier
            .size(NotesSpacing.space8)
            .border(Dp.Hairline, CittiTheme.colors.line, shape)
            .background(Color(argb), shape),
    )
}

/** `[-] 100 % [+]`; the `-` or `+` is disabled at the ends. */
@Composable
internal fun StepperRow(
    name: String,
    valueText: String,
    canLess: Boolean,
    canMore: Boolean,
    overridden: Boolean,
    onStep: (Int) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = NotesSize.touchMin),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space2),
    ) {
        BasicText(name, Modifier.weight(1f), style = CittiTheme.typography.body.colored())
        StepButton("−", stringResource(Res.string.theme_editor_text_size_less), canLess) { onStep(-1) }
        BasicText(valueText, Modifier.width(NotesSize.touchMin + NotesSpacing.space4), style = CittiTheme.typography.ui.colored())
        StepButton("+", stringResource(Res.string.theme_editor_text_size_more), canMore) { onStep(+1) }
        if (overridden) ResetButton(name, onReset)
    }
}

@Composable
private fun StepButton(
    glyph: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = CittiTheme.colors
    val shape = RoundedCornerShape(CittiTheme.shape.control)
    Box(
        Modifier
            .size(NotesSize.touchMin)
            .border(Dp.Hairline, colors.line, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            glyph,
            style = CittiTheme.typography.title.copy(color = if (enabled) colors.accentInk else colors.inkFaint),
            modifier = Modifier.testTag(label),
        )
    }
}

/** A labelled on/off switch; the state is also written out, never color alone. */
@Composable
internal fun SwitchRow(
    name: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    overridden: Boolean,
    onReset: () -> Unit,
    stateText: String,
    modifier: Modifier = Modifier,
) {
    val colors = CittiTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = NotesSize.touchMin).toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space3),
    ) {
        BasicText(name, Modifier.weight(1f), style = CittiTheme.typography.body.colored())
        BasicText(stateText, style = CittiTheme.typography.meta.colored(muted = true))
        val track = RoundedCornerShape(CittiTheme.shape.newNote)
        Box(
            Modifier
                .size(width = NotesSpacing.space10 + NotesSpacing.space1, height = NotesSpacing.space6)
                .background(if (checked) colors.accent else colors.line, track),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(NotesSpacing.space5).background(if (checked) colors.onAccent else colors.surfaceRaised, track))
        }
        if (overridden) ResetButton(name, onReset)
    }
}
