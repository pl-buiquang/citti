package app.citti.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.citti.ui.components.CittiTopBar
import app.citti.ui.components.Gutter
import app.citti.ui.components.IconButton
import app.citti.ui.components.RowBadge
import app.citti.ui.components.StateLabel
import app.citti.ui.components.colored
import app.citti.ui.resources.Res
import app.citti.ui.resources.action_back
import app.citti.ui.resources.badge_conflict
import app.citti.ui.resources.badge_pending
import app.citti.ui.resources.note_downloading
import app.citti.ui.resources.tree_root
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSize
import app.citti.ui.theme.NotesSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * The read-only Note (a stub until the M4 editor): ← and the title, a `meta` line with the Folder and the
 * badge, then the raw text, selectable. The text is split by lines into a lazy list so that a note of thousands
 * of lines stays smooth. [note] null shows only the top bar (still loading). "Downloading…" while the content is
 * not there; it fills in by itself when it arrives.
 */
@Composable
fun NoteViewScreen(
    note: NoteViewUiState?,
    listState: LazyListState,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(CittiTheme.colors.bg)
            .safeDrawingPadding(),
    ) {
        CittiTopBar(note?.title.orEmpty(), onBack = onBack)
        if (note == null) return@Column
        Gutter {
            Row(
                Modifier.padding(bottom = NotesSpacing.space2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space2),
            ) {
                BasicText(
                    note.folder.ifEmpty { stringResource(Res.string.tree_root) },
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = CittiTheme.typography.meta.colored(muted = true),
                )
                when (note.badge) {
                    RowBadge.Conflict -> StateLabel(stringResource(Res.string.badge_conflict), CittiTheme.colors.conflict)
                    RowBadge.Pending -> StateLabel(stringResource(Res.string.badge_pending), CittiTheme.colors.inkMuted)
                    null -> Unit
                }
            }
        }
        val content = note.content
        if (content == null) {
            Gutter {
                BasicText(stringResource(Res.string.note_downloading), style = CittiTheme.typography.meta.colored(muted = true))
            }
            return@Column
        }
        // Never rewrite the user's markdown: the lines are shown exactly as stored.
        val lines = remember(content) { content.split('\n') }
        val style = CittiTheme.typography.editor.colored()
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                itemsIndexed(lines, key = { index, _ -> index }) { _, line ->
                    BasicText(line, modifier = Modifier.fillMaxWidth().padding(horizontal = CittiTheme.shape.gutter), style = style)
                }
            }
        }
    }
}
