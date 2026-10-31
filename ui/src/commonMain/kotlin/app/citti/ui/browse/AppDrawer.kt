package app.citti.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import app.citti.core.note.FolderNode
import app.citti.core.sync.SyncStatus
import app.citti.ui.components.CittiDivider
import app.citti.ui.components.CittiIcon
import app.citti.ui.components.FolderRow
import app.citti.ui.components.Gutter
import app.citti.ui.components.Icon
import app.citti.ui.components.colored
import app.citti.ui.resources.Res
import app.citti.ui.resources.drawer_favorites
import app.citti.ui.resources.drawer_settings
import app.citti.ui.settings.AccountInfo
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSpacing
import org.jetbrains.compose.resources.stringResource

/** The drawer's entries. Each one is called after the drawer has closed. */
class DrawerActions(
    val onFavorites: () -> Unit,
    val onFolder: (path: String) -> Unit,
    val onSettings: () -> Unit,
)

/**
 * The burger drawer, top to bottom: `user@host` and the sync line, ★ Favorites, the top-level [folders] A–Z with
 * their recursive counts (Hidden folders are not in the tree), then ⚙ Settings. It scrolls when the Folders do
 * not fit. [account] null (still loading) leaves the account line out.
 */
@Composable
fun AppDrawer(
    account: AccountInfo?,
    status: SyncStatus?,
    now: Long,
    folders: List<FolderNode>,
    actions: DrawerActions,
) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(vertical = NotesSpacing.space2),
    ) {
        Gutter {
            if (account != null) {
                BasicText(
                    "${account.user}@${account.host}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = CittiTheme.typography.ui.colored(),
                )
            }
            BasicText(
                statusText(syncLine(status, now), status),
                modifier = Modifier.padding(bottom = NotesSpacing.space2),
                style = CittiTheme.typography.meta.colored(muted = true),
            )
        }
        CittiDivider()
        DrawerItem(CittiIcon.Star, stringResource(Res.string.drawer_favorites), actions.onFavorites)
        for (folder in folders) {
            FolderRow(folder.name, folder.totalNotes, onClick = { actions.onFolder(folder.path) }, chevron = false)
        }
        CittiDivider()
        DrawerItem(CittiIcon.Settings, stringResource(Res.string.drawer_settings), actions.onSettings)
    }
}

/** An icon and a label, as high as a row. */
@Composable
private fun DrawerItem(
    icon: CittiIcon,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = CittiTheme.shape.rowMin)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = CittiTheme.shape.gutter, vertical = NotesSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space3),
    ) {
        Icon(icon, color = CittiTheme.colors.inkMuted)
        BasicText(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = CittiTheme.typography.ui.colored())
    }
}
