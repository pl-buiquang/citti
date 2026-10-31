package app.citti.ui.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import app.citti.core.appearance.Appearance
import app.citti.ui.TestTags
import app.citti.ui.components.CittiIcon
import app.citti.ui.components.Icon
import app.citti.ui.resources.Res
import app.citti.ui.resources.theme_preview_body
import app.citti.ui.resources.theme_preview_code
import app.citti.ui.resources.theme_preview_excerpt
import app.citti.ui.resources.theme_preview_task
import app.citti.ui.resources.theme_preview_time
import app.citti.ui.resources.theme_preview_title
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * A list row and a note snippet drawn with the full custom theme of [appearance] (the Edit variant's Mode), so a
 * change shows at once and the dark colors can be checked on a light phone.
 */
@Composable
fun ThemePreviewCard(
    appearance: Appearance,
    modifier: Modifier = Modifier,
) {
    CittiTheme(appearance) {
        val colors = CittiTheme.colors
        val shape = RoundedCornerShape(CittiTheme.shape.media)
        Column(
            modifier
                .fillMaxWidth()
                .testTag(TestTags.THEME_PREVIEW)
                .clip(shape)
                .border(Dp.Hairline, colors.line, shape)
                .background(colors.bg),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = CittiTheme.shape.rowMin)
                    .background(colors.surface)
                    .padding(horizontal = CittiTheme.shape.gutter, vertical = NotesSpacing.space2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space3),
            ) {
                Icon(CittiIcon.StarFilled, color = colors.favorite)
                Column(Modifier.weight(1f)) {
                    BasicText(
                        stringResource(Res.string.theme_preview_title),
                        maxLines = 1,
                        style = CittiTheme.typography.rowTitle.copy(color = colors.ink),
                    )
                    BasicText(
                        stringResource(Res.string.theme_preview_excerpt),
                        maxLines = 1,
                        style = CittiTheme.typography.meta.copy(color = colors.inkMuted),
                    )
                }
                BasicText(stringResource(Res.string.theme_preview_time), style = CittiTheme.typography.meta.copy(color = colors.inkFaint))
            }
            Column(
                Modifier.padding(horizontal = CittiTheme.shape.gutter, vertical = NotesSpacing.space3),
                verticalArrangement = Arrangement.spacedBy(NotesSpacing.space2),
            ) {
                BasicText(stringResource(Res.string.theme_preview_title), style = CittiTheme.typography.title.copy(color = colors.ink))
                BasicText(
                    stringResource(Res.string.theme_preview_body),
                    maxLines = 2,
                    style = CittiTheme.typography.body.copy(color = colors.ink),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space2)) {
                    Box(
                        Modifier
                            .size(NotesSpacing.space5)
                            .background(colors.accent, RoundedCornerShape(CittiTheme.shape.control / 3)),
                    )
                    BasicText(
                        stringResource(Res.string.theme_preview_task),
                        style = CittiTheme.typography.ui.copy(color = colors.accentInk),
                    )
                }
                BasicText(
                    stringResource(Res.string.theme_preview_code),
                    Modifier.background(colors.accentSoft).padding(horizontal = NotesSpacing.space1),
                    style = CittiTheme.typography.editor.copy(color = colors.inkFaint),
                )
            }
        }
    }
}
