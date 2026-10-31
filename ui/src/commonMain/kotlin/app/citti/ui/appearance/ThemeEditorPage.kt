package app.citti.ui.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import app.citti.core.appearance.AccentSource
import app.citti.core.appearance.Appearance
import app.citti.core.appearance.BaseTheme
import app.citti.core.appearance.ColorToken
import app.citti.core.appearance.Corners
import app.citti.core.appearance.Density
import app.citti.core.appearance.FontRole
import app.citti.core.appearance.MAX_TEXT_SCALE
import app.citti.core.appearance.MIN_TEXT_SCALE
import app.citti.core.appearance.ModeChoice
import app.citti.core.appearance.ThemeChoice
import app.citti.core.appearance.ThemeFileCodec
import app.citti.ui.TestTags
import app.citti.ui.components.Banner
import app.citti.ui.components.BannerKind
import app.citti.ui.components.CittiTopBar
import app.citti.ui.components.SegmentedControl
import app.citti.ui.components.colored
import app.citti.ui.resources.Res
import app.citti.ui.resources.appearance_theme_index
import app.citti.ui.resources.appearance_theme_paper
import app.citti.ui.resources.appearance_theme_signal
import app.citti.ui.resources.settings_back
import app.citti.ui.resources.theme_editor_base
import app.citti.ui.resources.theme_editor_capital_labels
import app.citti.ui.resources.theme_editor_corners
import app.citti.ui.resources.theme_editor_custom_value
import app.citti.ui.resources.theme_editor_dark
import app.citti.ui.resources.theme_editor_density
import app.citti.ui.resources.theme_editor_edit_variant
import app.citti.ui.resources.theme_editor_fonts
import app.citti.ui.resources.theme_editor_from_wallpaper
import app.citti.ui.resources.theme_editor_labels
import app.citti.ui.resources.theme_editor_light
import app.citti.ui.resources.theme_editor_low_contrast
import app.citti.ui.resources.theme_editor_not_a_theme_file
import app.citti.ui.resources.theme_editor_off
import app.citti.ui.resources.theme_editor_on
import app.citti.ui.resources.theme_editor_overrides_kept
import app.citti.ui.resources.theme_editor_reset_all
import app.citti.ui.resources.theme_editor_shape
import app.citti.ui.resources.theme_editor_text_size
import app.citti.ui.resources.theme_editor_title
import app.citti.ui.settings.SectionHeader
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSpacing
import app.citti.ui.theme.get
import app.citti.ui.theme.resolveColors
import app.citti.ui.theme.toFamily
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.floor
import kotlin.math.roundToInt

/** The Appearance the editor page itself is drawn with: the base preset, no overrides, so a bad override cannot hide it. */
private fun Appearance.editorChrome(base: BaseTheme): Appearance =
    Appearance(
        theme =
            when (base) {
                BaseTheme.SIGNAL -> ThemeChoice.SIGNAL
                BaseTheme.PAPER -> ThemeChoice.PAPER
                BaseTheme.INDEX -> ThemeChoice.INDEX
            },
        mode = mode,
        accent = accent,
    )

/** The theme editor: one scrolling page (design brief §16). Every edit applies at once. */
@Composable
fun ThemeEditorPage(
    state: ThemeEditorState,
    actions: ThemeEditorActions,
) {
    CittiTheme(state.appearance.editorChrome(state.custom.base)) {
        val colors = CittiTheme.colors
        val editDark = state.editDark ?: colors.isDark
        Box(Modifier.fillMaxSize().background(colors.bg)) {
            Column(Modifier.fillMaxSize().testTag(TestTags.THEME_EDITOR)) {
                CittiTopBar(
                    title = stringResource(Res.string.theme_editor_title),
                    onBack = actions.onBack,
                    actionLabel = if (state.overrideCount > 0) stringResource(Res.string.theme_editor_reset_all) else null,
                    onAction = actions.onAskResetAll,
                )
                if (state.importError) {
                    Banner(
                        stringResource(Res.string.theme_editor_not_a_theme_file),
                        BannerKind.Error,
                        Modifier.testTag(TestTags.THEME_IMPORT_ERROR),
                        actionLabel = stringResource(Res.string.settings_back),
                        onAction = actions.onDismissImportError,
                    )
                }
                if (state.keptOverrides > 0) {
                    Banner(
                        pluralStringResource(Res.plurals.theme_editor_overrides_kept, state.keptOverrides, state.keptOverrides),
                        BannerKind.Info,
                    )
                }
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = CittiTheme.shape.gutter),
                    verticalArrangement = Arrangement.spacedBy(NotesSpacing.space3),
                ) {
                    EditorBody(state, editDark, actions)
                    Box(Modifier.padding(bottom = NotesSpacing.space8))
                }
            }
            EditorSheets(state, editDark, actions)
            EditorDialogs(state, actions)
        }
    }
}

@Composable
private fun EditorBody(
    state: ThemeEditorState,
    editDark: Boolean,
    actions: ThemeEditorActions,
) {
    val custom = state.custom
    val roles = custom.roles
    val dynamic = state.appearance.accent == AccentSource.DYNAMIC

    BasicText(stringResource(Res.string.theme_editor_base), style = CittiTheme.typography.meta.colored(muted = true))
    SegmentedControl(
        listOf(
            stringResource(Res.string.appearance_theme_signal) to BaseTheme.SIGNAL,
            stringResource(Res.string.appearance_theme_paper) to BaseTheme.PAPER,
            stringResource(Res.string.appearance_theme_index) to BaseTheme.INDEX,
        ),
        selected = custom.base,
        onSelect = actions.onBase,
    )
    BasicText(stringResource(Res.string.theme_editor_edit_variant), style = CittiTheme.typography.meta.colored(muted = true))
    SegmentedControl(
        listOf(stringResource(Res.string.theme_editor_light) to false, stringResource(Res.string.theme_editor_dark) to true),
        selected = editDark,
        onSelect = actions.onEditDark,
    )
    ThemePreviewCard(
        Appearance(ThemeChoice.CUSTOM, if (editDark) ModeChoice.DARK else ModeChoice.LIGHT, state.appearance.accent, custom),
    )

    val variant = state.colors(editDark)
    val resolved = resolveColors(custom.base.toFamily(), editDark, custom, AccentSource.THEME, null)
    for ((group, tokens) in COLOR_GROUPS) {
        SectionHeader(group)
        for (token in tokens) {
            val argb = resolved.get(token).toArgb().toLong() and 0xFFFFFFFFL
            val fromWallpaper = dynamic && token in DYNAMIC_TOKENS
            val ratio = if (token == ColorToken.INK_MUTED) inkMutedContrast(custom, editDark) else null
            val name = stringResource(token.label())
            ValueRow(
                name = name,
                value = if (fromWallpaper) stringResource(Res.string.theme_editor_from_wallpaper) else ThemeFileCodec.formatHex(argb),
                overridden = token in variant,
                onClick = { actions.onOpenColor(token) },
                onReset = { actions.onResetColor(token, editDark) },
                modifier = Modifier.testTag(TestTags.themeColor(token)),
                leading = { Swatch(argb) },
                detail =
                    ratio?.takeIf(::isLowContrast)?.let {
                        stringResource(Res.string.theme_editor_low_contrast, formatRatio(it))
                    },
            )
        }
    }

    SectionHeader(Res.string.theme_editor_fonts)
    for (role in FontRole.entries) {
        val face = roles.fonts[role]
        val name = stringResource(role.label())
        ValueRow(
            name = name,
            value = face?.let { it.displayName() ?: stringResource(it.systemLabel) } ?: stringResource(Res.string.theme_editor_base),
            overridden = face != null,
            onClick = { actions.onOpenFont(role) },
            onReset = { actions.onResetFont(role) },
            modifier = Modifier.testTag(TestTags.themeFont(role)),
        )
    }

    SectionHeader(Res.string.theme_editor_shape)
    val scale = roles.textScale ?: 1.0
    StepperRow(
        name = stringResource(Res.string.theme_editor_text_size),
        valueText = "${(scale * 100).roundToInt()} %",
        canLess = scale > MIN_TEXT_SCALE + 0.001,
        canMore = scale < MAX_TEXT_SCALE - 0.001,
        overridden = roles.textScale != null,
        onStep = actions.onTextScaleStep,
        onReset = actions.onResetTextScale,
    )
    val directDensity = roles.rowMin != null || roles.gutter != null
    SegmentRow(
        name = stringResource(Res.string.theme_editor_density),
        options = Density.entries.map { stringResource(it.label()) to it },
        selected = roles.density.takeIf { !directDensity },
        custom = directDensity,
        overridden = roles.density != null || directDensity,
        onSelect = actions.onDensity,
        onReset = actions.onResetDensity,
        tag = TestTags.THEME_DENSITY,
    )
    val directCorners = roles.radiusControl != null || roles.radiusMedia != null || roles.radiusNew != null
    SegmentRow(
        name = stringResource(Res.string.theme_editor_corners),
        options = Corners.entries.map { stringResource(it.label()) to it },
        selected = roles.corners.takeIf { !directCorners },
        custom = directCorners,
        overridden = roles.corners != null || directCorners,
        onSelect = actions.onCorners,
        onReset = actions.onResetCorners,
        tag = TestTags.THEME_CORNERS,
    )

    SectionHeader(Res.string.theme_editor_labels)
    val capitals = roles.labelCapitals ?: baseCapitals(custom.base)
    SwitchRow(
        name = stringResource(Res.string.theme_editor_capital_labels),
        checked = capitals,
        onChange = actions.onCapitals,
        overridden = roles.labelCapitals != null || roles.labelTracking != null,
        onReset = actions.onResetCapitals,
        stateText = stringResource(if (capitals) Res.string.theme_editor_on else Res.string.theme_editor_off),
        modifier = Modifier.testTag(TestTags.THEME_CAPITALS),
    )

    ImportExportSection(actions)
}

private fun baseCapitals(base: BaseTheme): Boolean =
    app.citti.ui.theme
        .notesShape(base.toFamily())
        .labelsUppercase

/** One decimal, rounded down so a ratio of 4.49 is never shown as the passing 4.5. */
internal fun formatRatio(ratio: Double): String {
    val tenths = floor(ratio * 10).toInt()
    return "${tenths / 10}.${tenths % 10}"
}

@Composable
private fun <T> SegmentRow(
    name: String,
    options: List<Pair<String, T>>,
    selected: T?,
    custom: Boolean,
    overridden: Boolean,
    onSelect: (T) -> Unit,
    onReset: () -> Unit,
    tag: String,
) {
    Column(Modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(NotesSpacing.space1)) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space2),
        ) {
            BasicText(name, Modifier.weight(1f), style = CittiTheme.typography.body.colored())
            if (custom) {
                BasicText(
                    stringResource(Res.string.theme_editor_custom_value),
                    style = CittiTheme.typography.meta.colored(muted = true),
                )
            }
            if (overridden) ResetButton(name, onReset)
        }
        SegmentedControl(options, selected, onSelect)
    }
}
