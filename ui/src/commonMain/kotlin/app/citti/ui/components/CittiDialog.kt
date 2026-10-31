package app.citti.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSpacing

/**
 * A modal card on a `scrim`, drawn over its parent (no window of its own, so its state stays in the screen's
 * controller). A tap on the scrim calls [onDismiss]; the caller handles Back.
 */
@Composable
fun CittiDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CittiTheme.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .safeDrawingPadding()
            .imePadding()
            .padding(CittiTheme.shape.gutter),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier
                .fillMaxWidth()
                .background(colors.surfaceRaised, RoundedCornerShape(CittiTheme.shape.control))
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(NotesSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(NotesSpacing.space3),
        ) {
            BasicText(title, Modifier.semantics { heading() }, style = CittiTheme.typography.title.colored())
            content()
        }
    }
}
