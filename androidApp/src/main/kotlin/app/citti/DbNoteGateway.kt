package app.citti

import app.cash.sqldelight.coroutines.asFlow
import app.citti.core.db.CittiDatabase
import app.citti.core.note.EditorDoc
import app.citti.core.note.LinkResolver
import app.citti.core.note.LinkTarget
import app.citti.core.note.NoteEditor
import app.citti.ui.note.LinkRef
import app.citti.ui.note.NoteGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** The [NoteGateway] of one Note over the local database; every call runs on `Dispatchers.IO`. */
class DbNoteGateway(
    private val db: CittiDatabase,
    private val localId: Long,
    private val editor: NoteEditor,
    private val links: LinkResolver,
    /** `SyncEngine.fetchContent` of this Note; false while signed out. */
    private val fetchContent: suspend () -> Boolean = { false },
    /** `SyncEngine.events`' drops of this Note, as the server Note's local id; empty while signed out. */
    private val dropped: () -> Flow<Long> = { emptyFlow() },
    /** Called after the Reading position was stored (the widgets redraw). */
    private val onViewLine: () -> Unit = {},
) : NoteGateway {
    private val query = db.noteQueries.selectForEditor(localId)

    override fun observe(): Flow<EditorDoc?> =
        query
            .asFlow()
            .map { withContext(Dispatchers.IO) { editor.load() } }
            .conflate()
            .distinctUntilChanged()

    override suspend fun save(
        text: String,
        baseEtag: String?,
        baseText: String?,
    ): NoteEditor.SaveResult = withContext(Dispatchers.IO) { editor.save(text, baseEtag, baseText) }

    override suspend fun toggleTask(
        box: Int,
        expected: String,
        baseEtag: String?,
        baseText: String?,
    ): NoteEditor.SaveResult = withContext(Dispatchers.IO) { editor.toggleTask(box, expected, baseEtag, baseText) }

    override suspend fun insertAt(
        offset: Int,
        block: String,
        baseEtag: String?,
        baseText: String?,
    ): NoteEditor.SaveResult = withContext(Dispatchers.IO) { editor.insertAt(offset, block, baseEtag, baseText) }

    override suspend fun fetchText(): Boolean = withContext(Dispatchers.IO) { fetchContent() }

    override fun dropped(): Flow<Long> = dropped.invoke()

    override suspend fun setViewLine(line: Int) {
        withContext(Dispatchers.IO) { db.noteQueries.setViewLine(line.toLong(), localId) }
        onViewLine()
    }

    override suspend fun viewLine(): Int? =
        withContext(Dispatchers.IO) {
            db.noteQueries
                .selectViewLine(localId)
                .executeAsOneOrNull()
                ?.view_line
                ?.toInt()
        }

    override fun savedText(): String? = editor.savedText

    override suspend fun resolve(
        folder: String,
        ref: LinkRef,
    ): LinkTarget =
        withContext(Dispatchers.IO) {
            when (ref) {
                is LinkRef.Wiki -> links.resolve(folder, ref.target)
                is LinkRef.Markdown -> links.resolveMarkdownLink(folder, ref.url)
            }
        }
}
