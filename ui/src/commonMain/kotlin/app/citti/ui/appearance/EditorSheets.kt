package app.citti.ui.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import app.citti.core.appearance.AccentSource
import app.citti.core.appearance.ColorToken
import app.citti.core.appearance.FontFace
import app.citti.core.appearance.FontRole
import app.citti.core.appearance.ThemeFileCodec
import app.citti.ui.TestTags
import app.citti.ui.components.ButtonKind
import app.citti.ui.components.CittiButton
import app.citti.ui.components.CittiTextField
import app.citti.ui.components.colored
import app.citti.ui.resources.Res
import app.citti.ui.resources.theme_editor_low_contrast
import app.citti.ui.resources.theme_sheet_base_value
import app.citti.ui.resources.theme_sheet_done
import app.citti.ui.resources.theme_sheet_hex
import app.citti.ui.resources.theme_sheet_reset
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSpacing
import app.citti.ui.theme.get
import app.citti.ui.theme.notesColors
import app.citti.ui.theme.rememberFontResolver
import app.citti.ui.theme.resolveColors
import app.citti.ui.theme.resolverKey
import app.citti.ui.theme.toFamily
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontFamily as ComposeFontFamily

/** The color and font sheets over the editor. */
@Composable
internal fun EditorSheets(
    state: ThemeEditorState,
    editDark: Boolean,
    actions: ThemeEditorActions,
) {
    when (val sheet = state.sheet) {
        is EditorSheet.PickColor -> ColorSheet(sheet.token, state, editDark, actions)
        is EditorSheet.PickFont -> FontSheet(sheet.role, state, actions)
        null -> Unit
    }
}

@Composable
private fun SheetFrame(
    onDismiss: () -> Unit,
    tag: String,
    content: @Composable () -> Unit,
) {
    val colors = CittiTheme.colors
    val shape = RoundedCornerShape(topStart = CittiTheme.shape.control, topEnd = CittiTheme.shape.control)
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(onClick = onDismiss)
            .safeDrawingPadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.surfaceRaised, shape)
                .clickable(enabled = false) {}
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CittiTheme.shape.gutter, vertical = NotesSpacing.space3)
                .testTag(tag),
            verticalArrangement = Arrangement.spacedBy(NotesSpacing.space3),
        ) {
            content()
        }
    }
}

@Composable
private fun ColorSheet(
    token: ColorToken,
    state: ThemeEditorState,
    editDark: Boolean,
    actions: ThemeEditorActions,
) {
    val custom = state.custom
    val current =
        resolveColors(custom.base.toFamily(), editDark, custom, AccentSource.THEME, null).get(token).toArgb().toLong() and 0xFFFFFFFFL
    val base = notesColors(custom.base.toFamily(), editDark).get(token).toArgb().toLong() and 0xFFFFFFFFL
    val alpha = token in ALPHA_TOKENS
    var hsv by remember(token, editDark) { mutableStateOf(argbToHsv(current)) }
    var hex by remember(token, editDark) { mutableStateOf(ThemeFileCodec.formatHex(current)) }
    val draft = hsvToArgb(hsv)
    val name = stringResource(token.label())

    fun commit(h: Hsv) = actions.onSetColor(token, editDark, hsvToArgb(h))
    SheetFrame(actions.onCloseSheet, TestTags.THEME_COLOR_SHEET) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space3)) {
            Swatch(draft)
            BasicText(name, Modifier.weight(1f), style = CittiTheme.typography.rowTitle.colored())
            if (token == ColorToken.INK_MUTED) {
                val ratio = inkMutedContrast(custom.withColor(token, editDark, draft), editDark)
                BasicText(
                    if (isLowContrast(ratio)) {
                        stringResource(Res.string.theme_editor_low_contrast, formatRatio(ratio))
                    } else {
                        "${formatRatio(ratio)}:1"
                    },
                    style =
                        CittiTheme.typography.meta.copy(
                            color = if (isLowContrast(ratio)) CittiTheme.colors.danger else CittiTheme.colors.inkMuted,
                        ),
                    modifier = Modifier.testTag(TestTags.THEME_CONTRAST),
                )
            }
        }
        HsvPicker(
            hsv,
            withAlpha = alpha,
            onChange = {
                hsv = it
                hex = ThemeFileCodec.formatHex(hsvToArgb(it))
            },
            onCommit = { commit(it) },
        )
        CittiTextField(
            value = hex,
            onValueChange = { text ->
                hex = text
                ThemeFileCodec.parseHex(text.trim())?.takeIf { alpha || text.trim().length == 7 }?.let {
                    hsv = argbToHsv(it)
                    actions.onSetColor(token, editDark, it)
                }
            },
            label = stringResource(Res.string.theme_sheet_hex),
            modifier = Modifier.testTag(TestTags.THEME_HEX),
            keyboardType = KeyboardType.Ascii,
        )
        BasicText(
            stringResource(Res.string.theme_sheet_base_value, ThemeFileCodec.formatHex(base)),
            style = CittiTheme.typography.meta.colored(muted = true),
        )
        if (token in state.colors(editDark)) {
            CittiButton(
                stringResource(Res.string.theme_sheet_reset),
                {
                    actions.onResetColor(token, editDark)
                    actions.onCloseSheet()
                },
                kind = ButtonKind.Quiet,
            )
        }
        CittiButton(stringResource(Res.string.theme_sheet_done), actions.onCloseSheet, Modifier.testTag(TestTags.THEME_SHEET_DONE))
    }
}

private fun app.citti.core.appearance.CustomTheme.withColor(
    token: ColorToken,
    dark: Boolean,
    argb: Long,
) = if (dark) copy(dark = this.dark + (token to argb)) else copy(light = light + (token to argb))

@Composable
private fun FontSheet(
    role: FontRole,
    state: ThemeEditorState,
    actions: ThemeEditorActions,
) {
    val fonts = rememberFontResolver()
    val selected = state.custom.roles.fonts[role]
    SheetFrame(actions.onCloseSheet, TestTags.THEME_FONT_SHEET) {
        BasicText(stringResource(role.label()), style = CittiTheme.typography.rowTitle.colored())
        for (face in FontFace.entries) {
            val family: ComposeFontFamily = face.resolverKey()?.let { fonts.fontFor(it) } ?: ComposeFontFamily.Default
            val label = face.displayName() ?: stringResource(face.systemLabel)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = app.citti.ui.theme.NotesSize.touchMin)
                    .clickable(role = Role.RadioButton) {
                        actions.onSetFont(role, face)
                        actions.onCloseSheet()
                    }.testTag(TestTags.themeFace(face)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space3),
            ) {
                val selectedFace = selected == face
                val color = if (selectedFace) CittiTheme.colors.accentInk else CittiTheme.colors.ink
                BasicText(if (selectedFace) "✓" else " ", style = CittiTheme.typography.body.copy(color = color))
                BasicText(label, style = CittiTheme.typography.body.copy(color = color, fontFamily = family))
            }
        }
    }
}
