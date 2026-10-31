package app.citti.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.citti.core.note.NoteBadge
import app.citti.ui.resources.Res
import app.citti.ui.resources.action_back
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSize
import app.citti.ui.theme.NotesSpacing
import org.jetbrains.compose.resources.stringResource

/** Text roles resolved against the current theme colors. Components use these instead of raw colors. */
@Composable
fun TextStyle.colored(muted: Boolean = false): TextStyle = copy(color = if (muted) CittiTheme.colors.inkMuted else CittiTheme.colors.ink)

/** Horizontal screen gutter from the theme. */
@Composable
fun Gutter(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = CittiTheme.shape.gutter)) { content() }
}

enum class ButtonKind { Primary, Quiet, Danger }

/** A labelled action (see design-system Button). 44 dp minimum, stretches to the width. */
@Composable
fun CittiButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true,
    /** Shows an inline [ProgressRing] next to the label and ignores taps (a server check is running). */
    busy: Boolean = false,
) {
    val colors = CittiTheme.colors
    val shape = RoundedCornerShape(CittiTheme.shape.control)
    val (fill, text) =
        when (kind) {
            ButtonKind.Primary -> colors.accent to colors.onAccent
            ButtonKind.Quiet -> Color.Transparent to colors.accentInk
            ButtonKind.Danger -> colors.danger to colors.onAccent
        }
    val shown = if (CittiTheme.shape.labelsUppercase) label.uppercase() else label
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = NotesSize.touchMin)
            .background(fill, shape)
            .let { if (kind == ButtonKind.Quiet) it.border(Dp.Hairline, colors.line, shape) else it }
            .clickable(enabled = enabled && !busy, onClick = onClick)
            .padding(horizontal = NotesSpacing.space4),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space2)) {
            if (busy) ProgressRing(color = text)
            BasicText(
                shown,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = CittiTheme.typography.ui.copy(color = if (enabled) text else colors.inkFaint),
            )
        }
    }
}

/** A link-style action: no fill, no outline. */
@Composable
fun CittiTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .heightIn(min = NotesSize.touchMin)
            .clickable(onClick = onClick)
            .padding(horizontal = NotesSpacing.space3),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = CittiTheme.typography.ui.copy(color = CittiTheme.colors.accentInk))
    }
}

/** A labelled single-line field. [secret] hides the text (app password). */
@Composable
fun CittiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    /** Shown under the field in `danger`. */
    error: String? = null,
    /** With [onImeAction], the keyboard's action key (Done) runs it. */
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    val colors = CittiTheme.colors
    val shape = RoundedCornerShape(CittiTheme.shape.control)
    var focused by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(NotesSpacing.space1)) {
        BasicText(label, style = CittiTheme.typography.meta.colored(muted = true))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = CittiTheme.typography.body.colored(),
            cursorBrush = SolidColor(colors.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = if (secret) KeyboardType.Password else keyboardType,
                    autoCorrectEnabled = false,
                    imeAction = imeAction,
                ),
            keyboardActions = KeyboardActions(onAny = { onImeAction?.invoke() }),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                    .background(colors.surface, shape)
                    .border(Dp.Hairline, if (focused) colors.focus else colors.line, shape)
                    .onFocusChanged { focused = it.isFocused }
                    .padding(horizontal = NotesSpacing.space3),
            decorationBox = { inner ->
                Box(Modifier.heightIn(min = NotesSize.touchMin), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        BasicText(placeholder, style = CittiTheme.typography.body.copy(color = colors.inkFaint))
                    }
                    inner()
                }
            },
        )
        if (error != null) BasicText(error, style = CittiTheme.typography.meta.copy(color = colors.danger))
    }
}

enum class BannerKind { Offline, Error }

/**
 * Full-width strip under the top bar. Offline has no action; Error has one (Retry, Sign in again) and
 * optionally a second one before it (Details).
 */
@Composable
fun Banner(
    message: String,
    kind: BannerKind,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val colors = CittiTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = CittiTheme.shape.gutter, vertical = NotesSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space3),
    ) {
        BasicText(
            message,
            modifier = Modifier.weight(1f),
            style = CittiTheme.typography.meta.copy(color = if (kind == BannerKind.Error) colors.danger else colors.ink),
        )
        if (secondaryLabel != null && onSecondary != null) CittiTextButton(secondaryLabel, onSecondary)
        if (actionLabel != null && onAction != null) CittiTextButton(actionLabel, onAction)
    }
}

/** Top bar: title on the left, optional text action on the right. */
@Composable
fun CittiTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = NotesSize.barHeight)
            .padding(start = if (onBack == null) CittiTheme.shape.gutter else 0.dp, end = CittiTheme.shape.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconButton(CittiIcon.Back, stringResource(Res.string.action_back), onBack)
        BasicText(
            title,
            modifier = Modifier.weight(1f).semantics { heading() },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = CittiTheme.typography.title.colored(),
        )
        if (actionLabel != null && onAction != null) CittiTextButton(actionLabel, onAction)
    }
}

enum class RowBadge { Pending, Conflict }

fun NoteBadge.toRowBadge(): RowBadge =
    when (this) {
        NoteBadge.Pending -> RowBadge.Pending
        NoteBadge.Conflict -> RowBadge.Conflict
    }

/** State is words, not only color. [outline] draws a hairline box around it (the Hidden badge). */
@Composable
fun StateLabel(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    outline: Boolean = false,
) {
    val shape = RoundedCornerShape(CittiTheme.shape.control)
    BasicText(
        if (CittiTheme.shape.labelsUppercase) text.uppercase() else text,
        modifier =
            if (outline) {
                modifier.border(Dp.Hairline, color, shape).padding(horizontal = NotesSpacing.space1)
            } else {
                modifier
            },
        style = CittiTheme.typography.label.copy(color = color),
    )
}

/** A centered message for an empty screen, with an optional action. */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = CittiTheme.shape.gutter, vertical = NotesSpacing.space8),
        verticalArrangement = Arrangement.spacedBy(NotesSpacing.space3, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            message,
            style =
                CittiTheme.typography.body
                    .colored(muted = true)
                    .copy(textAlign = TextAlign.Center),
        )
        if (actionLabel != null && onAction != null) CittiTextButton(actionLabel, onAction)
    }
}

/** A hairline rule between groups of rows (the drawer), in the `line` color. */
@Composable
fun CittiDivider(modifier: Modifier = Modifier) {
    val color = CittiTheme.colors.line
    Spacer(
        modifier
            .fillMaxWidth()
            .padding(vertical = NotesSpacing.space2)
            .drawBehind { drawLine(color, Offset.Zero, Offset(size.width, 0f), strokeWidth = Stroke.HairlineWidth) },
    )
}
