package app.citti.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import app.citti.ui.resources.Res
import app.citti.ui.resources.badge_conflict
import app.citti.ui.resources.badge_favorite
import app.citti.ui.resources.badge_hidden
import app.citti.ui.resources.badge_pending
import app.citti.ui.resources.folder_count
import app.citti.ui.resources.tree_root
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSpacing
import org.jetbrains.compose.resources.stringResource

/** Separator between the Folder and the Excerpt on a row's second line. */
private const val DOT = " · "

/** Row tap target; rows are at least `rowMin` high, which is never under `touchMin`. */
private fun Modifier.rowClick(onClick: (() -> Unit)?): Modifier =
    if (onClick ==
        null
    ) {
        this
    } else {
        clickable(role = Role.Button, onClick = onClick)
    }

private const val MAX_FOLDER_WITH_SNIPPET = 24

/**
 * A Note row. Line 1: the title. Line 2: `folder · excerpt` ([snippet] replaces the Excerpt in
 * Search). [folder] null hides the Folder (Tree mode: the Breadcrumb shows it); "" is the Vault
 * root, shown as "Notes". Then the date, the Favorite star and the badges.
 */
@Composable
fun NoteRow(
    title: String,
    folder: String?,
    modifier: Modifier = Modifier,
    excerpt: String? = null,
    snippet: AnnotatedString? = null,
    date: String? = null,
    favorite: Boolean = false,
    badge: RowBadge? = null,
    hidden: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = CittiTheme.colors
    // With a snippet the hit matters more than the whole path: keep the end of a long Folder so the hit stays on the line.
    val folderText =
        folder?.ifEmpty { stringResource(Res.string.tree_root) }?.let {
            if (snippet != null && it.length > MAX_FOLDER_WITH_SNIPPET) "…" + it.takeLast(MAX_FOLDER_WITH_SNIPPET - 1) else it
        }
    val detail: AnnotatedString? = snippet ?: excerpt?.takeIf { it.isNotBlank() }?.let(::AnnotatedString)
    val second =
        buildAnnotatedString {
            if (folderText != null) append(folderText)
            if (folderText != null && detail != null) append(DOT)
            if (detail != null) append(detail)
        }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = CittiTheme.shape.rowMin)
            .rowClick(onClick)
            .padding(horizontal = CittiTheme.shape.gutter, vertical = NotesSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space3),
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = CittiTheme.typography.rowTitle.colored())
            if (second.isNotEmpty()) {
                BasicText(second, maxLines = 1, overflow = TextOverflow.Ellipsis, style = CittiTheme.typography.meta.colored(muted = true))
            }
        }
        if (date != null) BasicText(date, style = CittiTheme.typography.meta.colored(muted = true))
        if (favorite) {
            val label = stringResource(Res.string.badge_favorite)
            Icon(
                CittiIcon.Star,
                Modifier.size(NotesSpacing.space4).semantics { contentDescription = label },
                color = colors.favorite,
            )
        }
        when (badge) {
            RowBadge.Conflict -> StateLabel(stringResource(Res.string.badge_conflict), colors.conflict)
            RowBadge.Pending -> StateLabel(stringResource(Res.string.badge_pending), colors.inkMuted)
            null -> Unit
        }
        if (hidden) StateLabel(stringResource(Res.string.badge_hidden), colors.inkMuted, outline = true)
    }
}

/**
 * A Folder row in Tree mode and the drawer: icon, name (UI face), recursive Note count, and a chevron when
 * [chevron] (Tree mode, where the row opens a level). Same height and padding as [NoteRow], so the two line up
 * when mixed.
 */
@Composable
fun FolderRow(
    name: String,
    count: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    chevron: Boolean = onClick != null,
) {
    val colors = CittiTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = CittiTheme.shape.rowMin)
            .rowClick(onClick)
            .padding(horizontal = CittiTheme.shape.gutter, vertical = NotesSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space3),
    ) {
        Icon(CittiIcon.Folder, color = colors.inkMuted)
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space2),
        ) {
            BasicText(
                name,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = CittiTheme.typography.ui.colored(),
            )
            BasicText(stringResource(Res.string.folder_count, count), style = CittiTheme.typography.meta.colored(muted = true))
        }
        if (chevron) Icon(CittiIcon.Chevron, color = colors.inkMuted)
    }
}
