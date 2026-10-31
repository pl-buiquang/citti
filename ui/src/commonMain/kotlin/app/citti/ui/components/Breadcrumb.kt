package app.citti.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.citti.ui.resources.Res
import app.citti.ui.resources.tree_root
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSize
import app.citti.ui.theme.NotesSpacing
import org.jetbrains.compose.resources.stringResource

/** The Folder paths a Breadcrumb shows for [folder]: "" (the Vault root), then each ancestor, then [folder]. */
fun breadcrumbPaths(folder: String): List<String> =
    listOf("") +
        if (folder.isEmpty()) {
            emptyList()
        } else {
            folder.split('/').runningReduce { parent, name -> "$parent/$name" }
        }

/**
 * The path from "Notes" (the Vault root) to [folder]. Every segment but the last opens its Folder
 * through [onOpen]. Scrolls horizontally and keeps the end in view.
 */
@Composable
fun Breadcrumb(
    folder: String,
    onOpen: (path: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CittiTheme.colors
    val scroll = rememberScrollState()
    LaunchedEffect(folder, scroll.maxValue) { scroll.animateScrollTo(scroll.maxValue) }
    val paths = breadcrumbPaths(folder)
    val root = stringResource(Res.string.tree_root)
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = CittiTheme.shape.gutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space1),
    ) {
        paths.forEachIndexed { i, path ->
            val last = i == paths.lastIndex
            if (i > 0) Icon(CittiIcon.Chevron, Modifier.size(NotesSpacing.space4), color = colors.inkFaint)
            val name = if (path.isEmpty()) root else path.substringAfterLast('/')
            Box(
                Modifier
                    .sizeIn(minWidth = NotesSize.touchMin, minHeight = NotesSize.touchMin)
                    .then(
                        if (last) {
                            Modifier.semantics { heading() }
                        } else {
                            Modifier.clickable(role = Role.Button) { onOpen(path) }
                        },
                    ).padding(horizontal = NotesSpacing.space1),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    name,
                    maxLines = 1,
                    style = CittiTheme.typography.ui.copy(color = if (last) colors.ink else colors.accentInk),
                )
            }
        }
    }
}
