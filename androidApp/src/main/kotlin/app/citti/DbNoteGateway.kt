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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** The [NoteGateway] of one Note over the local database; every call runs on `Dispatchers.IO`. */
class DbNoteGateway(
    private val db: CittiDatabase,
    localId: Long,
    private val editor: NoteEditor,
    private val links: LinkResolver,
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
    ): NoteEditor.SaveResult = withContext(Dispatchers.IO) { editor.save(text, baseEtag) }

    override suspend fun toggleTask(
        box: Int,
        expected: String,
        baseEtag: String?,
    ): NoteEditor.SaveResult = withContext(Dispatchers.IO) { editor.toggleTask(box, expected, baseEtag) }

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
