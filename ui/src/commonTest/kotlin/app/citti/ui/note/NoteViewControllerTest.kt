package app.citti.ui.note

import app.citti.ui.components.RowBadge
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class NoteViewControllerTest {
    private val note = NoteViewUiState("Title", "a/b", content = null, badge = RowBadge.Pending)

    @Test
    fun loadingThenShownThenUpdated() =
        runTest(UnconfinedTestDispatcher()) {
            val source = MutableStateFlow<NoteViewUiState?>(null)
            val c = NoteViewController(backgroundScope, source.filterNotNull())
            assertEquals(NoteViewState.Loading, c.state.value)
            source.value = note
            assertEquals(NoteViewState.Shown(note), c.state.value)
            source.value = note.copy(content = "text")
            assertEquals(NoteViewState.Shown(note.copy(content = "text")), c.state.value)
        }

    @Test
    fun aDeletedNoteIsGone() =
        runTest(UnconfinedTestDispatcher()) {
            val source = MutableStateFlow<NoteViewUiState?>(note)
            val c = NoteViewController(backgroundScope, source)
            assertEquals(NoteViewState.Shown(note), c.state.value)
            source.value = null
            assertEquals(NoteViewState.Gone, c.state.value)
        }
}
