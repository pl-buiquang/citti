package app.citti.ui.note

import app.citti.ui.components.RowBadge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The Note shown read-only. [content] is null until it is downloaded; [folder] "" is the Vault root. */
data class NoteViewUiState(
    val title: String,
    val folder: String,
    val content: String?,
    val badge: RowBadge?,
)

/** What the Note screen shows. [Gone]: the Note was deleted (by a sync), so the screen pops itself. */
sealed interface NoteViewState {
    data object Loading : NoteViewState

    data object Gone : NoteViewState

    data class Shown(
        val note: NoteViewUiState,
    ) : NoteViewState
}

/**
 * State holder of one Note route entry (the read-only stub that the M4 editor replaces). [note] comes from the
 * local DB and emits null once the Note no longer exists.
 */
class NoteViewController(
    scope: CoroutineScope,
    note: Flow<NoteViewUiState?>,
) {
    val state: StateFlow<NoteViewState> =
        note
            .map { if (it == null) NoteViewState.Gone else NoteViewState.Shown(it) }
            .stateIn(scope, SharingStarted.Eagerly, NoteViewState.Loading)
}
