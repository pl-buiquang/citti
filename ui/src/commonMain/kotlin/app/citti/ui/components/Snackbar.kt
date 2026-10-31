package app.citti.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import app.citti.ui.resources.Res
import app.citti.ui.resources.action_open_exclusions
import app.citti.ui.resources.conflict_snackbar
import app.citti.ui.resources.deleted_snackbar
import app.citti.ui.resources.dropped_snackbar
import app.citti.ui.resources.import_done
import app.citti.ui.resources.link_create
import app.citti.ui.resources.link_missing
import app.citti.ui.resources.link_unsynced
import app.citti.ui.resources.toast_deleted
import app.citti.ui.resources.toast_favorite_added
import app.citti.ui.resources.toast_favorite_removed
import app.citti.ui.resources.upload_failed
import app.citti.ui.resources.upload_offline
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSize
import app.citti.ui.theme.NotesSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource

/** A short message that outlives the screen that raised it (the Note pops itself after a deletion). */
sealed interface Snack {
    /** Your edit was based on an old server version; the text is in the Conflict copy [copyTitle]. */
    data class ConflictCopy(
        val copyTitle: String,
    ) : Snack

    /** The Note was deleted while open; the text is in [copyTitle]. */
    data class Deleted(
        val copyTitle: String,
    ) : Snack

    /** The open New note was dropped at Sync: the server already had [title] in [folder], and the screen shows it. */
    data class Dropped(
        val title: String,
        val folder: String,
    ) : Snack

    /** A tap on a link to no Note; its Create note action opens the New note dialog on [folder] and [title]. */
    data class LinkMissing(
        val name: String,
        val folder: String,
        val title: String,
    ) : Snack

    data object LinkUnsynced : Snack

    /** The automatic Import from Obsidian read the Daily notes settings (shown only when it read something). */
    data object DailyImported : Snack

    /** The ⋮ menu's Favorite toast. */
    data object FavoriteAdded : Snack

    data object FavoriteRemoved : Snack

    /** The ⋮ menu's Delete: shown after the Note pops. */
    data object NoteDeleted : Snack

    /** Insert image while offline: nothing was inserted. */
    data object UploadOffline : Snack

    /** Insert image failed ([reason]: the server's status or the error); nothing was inserted. */
    data class UploadFailed(
        val reason: String,
    ) : Snack
}

/** The one snackbar of the app; a new one replaces the shown one. */
class SnackbarState {
    private val _current = MutableStateFlow<Snack?>(null)
    val current: StateFlow<Snack?> = _current

    fun show(snack: Snack) {
        _current.value = snack
    }

    fun dismiss(snack: Snack) {
        if (_current.value == snack) _current.value = null
    }
}

/** Shows [state] at the bottom of its parent; [onAction] runs the action of the snackbar that has one. */
@Composable
fun SnackbarHost(
    state: SnackbarState,
    onAction: (Snack) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snack by state.current.collectAsState()
    val shown = snack ?: return
    LaunchedEffect(shown) {
        delay(SNACK_MILLIS)
        state.dismiss(shown)
    }
    val message =
        when (shown) {
            is Snack.ConflictCopy -> stringResource(Res.string.conflict_snackbar, shown.copyTitle)
            is Snack.Deleted -> stringResource(Res.string.deleted_snackbar, shown.copyTitle)
            is Snack.Dropped -> stringResource(Res.string.dropped_snackbar)
            is Snack.LinkMissing -> stringResource(Res.string.link_missing, shown.name)
            Snack.LinkUnsynced -> stringResource(Res.string.link_unsynced)
            Snack.DailyImported -> stringResource(Res.string.import_done)
            Snack.FavoriteAdded -> stringResource(Res.string.toast_favorite_added)
            Snack.FavoriteRemoved -> stringResource(Res.string.toast_favorite_removed)
            Snack.NoteDeleted -> stringResource(Res.string.toast_deleted)
            Snack.UploadOffline -> stringResource(Res.string.upload_offline)
            is Snack.UploadFailed -> stringResource(Res.string.upload_failed, shown.reason)
        }
    val action =
        when (shown) {
            Snack.LinkUnsynced -> stringResource(Res.string.action_open_exclusions)
            is Snack.LinkMissing -> stringResource(Res.string.link_create)
            else -> null
        }
    val colors = CittiTheme.colors
    Box(modifier.fillMaxWidth().padding(CittiTheme.shape.gutter), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.ink, RoundedCornerShape(CittiTheme.shape.control))
                .heightIn(min = NotesSize.touchMin)
                .padding(horizontal = NotesSpacing.space3, vertical = NotesSpacing.space2)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space3),
        ) {
            BasicText(message, Modifier.weight(1f), style = CittiTheme.typography.meta.copy(color = colors.bg))
            if (action != null) {
                Box(
                    Modifier
                        .heightIn(min = NotesSize.touchMin)
                        .clickable(role = Role.Button) {
                            onAction(shown)
                            state.dismiss(shown)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        action,
                        style = CittiTheme.typography.ui.copy(color = colors.bg, textDecoration = TextDecoration.Underline),
                    )
                }
            }
        }
    }
}

private const val SNACK_MILLIS = 5_000L
