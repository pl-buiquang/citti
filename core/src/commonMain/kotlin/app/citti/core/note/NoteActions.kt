package app.citti.core.note

import app.citti.core.db.CittiDatabase
import app.citti.core.settings.ExclusionKind
import app.citti.core.settings.ExclusionRepository
import app.citti.core.sync.categoryMatchesPrefix
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The answer to [NoteActions.rename] and [NoteActions.move]. */
sealed interface RenameResult {
    data object Done : RenameResult

    /** The title (or Folder) is the one the Note already has: nothing was written. */
    data object Unchanged : RenameResult

    /** [folder] already has a Note with that exact title; nothing was written. */
    data class Clash(
        val folder: String,
    ) : RenameResult

    data class Invalid(
        val reason: NameError,
    ) : RenameResult

    data object ReadOnly : RenameResult

    /** The Note no longer exists, or is deleted. */
    data object Gone : RenameResult
}

/** The sync state Note info shows. */
enum class InfoState { Synced, Pending, ConflictCopy }

/** What the Note info sheet shows. Metadata only: no backlinks. */
data class NoteInfo(
    val title: String,
    /** The Folder path, `""` for the Vault root (shown as "Notes"). */
    val folder: String,
    /** Epoch seconds. */
    val modified: Long,
    /** UTF-8 bytes of the text. */
    val bytes: Int,
    /** Runs of letters or digits. */
    val words: Int,
    /** Code points. */
    val chars: Int,
    /** `\n` count + 1, 0 for an empty text. */
    val lines: Int,
    val state: InfoState,
    val favorite: Boolean,
    val readonly: Boolean,
    /** The Hidden folder the Note lies in, if any. */
    val hiddenUnder: String?,
    /** The Conflict copy of this Note (local id, title), if there is one. */
    val conflictCopy: Pair<Long, String>?,
)

/** Counts of a Note text, as Note info shows them. */
data class TextCounts(
    val bytes: Int,
    val words: Int,
    val chars: Int,
    val lines: Int,
) {
    companion object {
        fun of(text: String): TextCounts {
            var words = 0
            var chars = 0
            var lines = if (text.isEmpty()) 0 else 1
            var inWord = false
            var i = 0
            while (i < text.length) {
                val c = text[i]
                val pair = c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
                chars++
                if (c == '\n') lines++
                // Supplementary code points (emoji…) end a word; combining marks continue one ("e" + U+0301).
                val wordChar = !pair && (c.isLetterOrDigit() || (inWord && c.isMark()))
                if (wordChar && !inWord) words++
                inWord = wordChar
                i += if (pair) 2 else 1
            }
            return TextCounts(text.encodeToByteArray().size, words, chars, lines)
        }

        private fun Char.isMark() =
            category == CharCategory.NON_SPACING_MARK ||
                category == CharCategory.COMBINING_SPACING_MARK ||
                category == CharCategory.ENCLOSING_MARK
    }
}

/**
 * The Note menu's actions: Favorite, Rename, Move, Delete and Note info. Every write marks the row DIRTY (a NEW row
 * stays NEW) and calls [afterEdit], so it is pushed about 2 s later. Read-only Notes are refused: the push skips
 * them. Rename and Move never rewrite the links in other Notes.
 *
 * Rename and Move check the target `(folder, title)` the way creation does ([NoteCreator.check]): the local DB, a
 * Folder refresh capped at [CREATION_CHECK_MS], the local DB again. Offline or on a timeout only the local check
 * applies; the server then renames a clashing Note to `Title (n)` and the next pull brings that title back. Titles
 * match exactly, as on the server: a case-only rename is a rename. [clock] returns epoch seconds.
 */
class NoteActions(
    db: CittiDatabase,
    private val creator: NoteCreator,
    private val exclusions: ExclusionRepository,
    /** `AfterEditPush.schedule()`. */
    private val afterEdit: () -> Unit,
    private val clock: () -> Long,
    private val countDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val checkTimeoutMs: Long = CREATION_CHECK_MS,
) {
    private val notes = db.noteQueries

    /** Sets the Favorite flag; false when the Note is gone, deleted or read-only. */
    fun setFavorite(
        localId: Long,
        favorite: Boolean,
    ): Boolean {
        val changed = notes.setFavorite(favorite, localId).value > 0
        if (changed) afterEdit()
        return changed
    }

    /** Renames the Note in its Folder; [title] is trimmed. */
    suspend fun rename(
        localId: Long,
        title: String,
    ): RenameResult {
        val row = notes.selectByLocalId(localId).executeAsOneOrNull()
        val newTitle = title.trim()
        return when {
            row == null || row.sync_state == DELETED -> {
                RenameResult.Gone
            }

            row.readonly -> {
                RenameResult.ReadOnly
            }

            else -> {
                NameRules.validateTitle(newTitle)?.let { RenameResult.Invalid(it) }
                    ?: renameMove(localId, row.category, row.title, row.category, newTitle)
            }
        }
    }

    /** Moves the Note into [folder] (normalised; refused under an Unsynced folder), keeping its title. */
    suspend fun move(
        localId: Long,
        folder: String,
    ): RenameResult {
        val row = notes.selectByLocalId(localId).executeAsOneOrNull()
        return when {
            row == null || row.sync_state == DELETED -> {
                RenameResult.Gone
            }

            row.readonly -> {
                RenameResult.ReadOnly
            }

            else -> {
                NameRules.normaliseFolder(folder, exclusions).fold(
                    onSuccess = { renameMove(localId, row.category, row.title, it, row.title) },
                    onFailure = { RenameResult.Invalid((it as InvalidNameException).error) },
                )
            }
        }
    }

    /**
     * Deletes the Note: a never-pushed NEW row outright, any other row becomes DELETED and the push sends the
     * DELETE (to the server's trash). False when it is gone, already deleted or read-only.
     */
    fun delete(localId: Long): Boolean {
        val deleted = notes.deleteNew(localId).value > 0 || notes.markDeletedByUser(localId).value > 0
        if (deleted) afterEdit()
        return deleted
    }

    /** The Note info of a non-deleted Note; the text counts run on [countDispatcher]. */
    suspend fun info(localId: Long): NoteInfo? {
        val row = notes.selectByLocalId(localId).executeAsOneOrNull() ?: return null
        if (row.sync_state == DELETED) return null
        val counts = withContext(countDispatcher) { TextCounts.of(row.content.orEmpty()) }
        val hiddenUnder =
            exclusions
                .list(ExclusionKind.HIDDEN)
                .filter { categoryMatchesPrefix(row.category, it) }
                .minByOrNull { it.length }
        val copy = notes.selectConflictCopyOf(localId).executeAsOneOrNull()?.let { it.local_id to it.title }
        val state =
            when {
                row.conflict_of != null -> InfoState.ConflictCopy
                row.sync_state == SYNCED -> InfoState.Synced
                else -> InfoState.Pending
            }
        return NoteInfo(
            title = row.title,
            folder = row.category,
            modified = row.modified,
            bytes = counts.bytes,
            words = counts.words,
            chars = counts.chars,
            lines = counts.lines,
            state = state,
            favorite = row.favorite,
            readonly = row.readonly,
            hiddenUnder = hiddenUnder,
            conflictCopy = copy,
        )
    }

    private suspend fun renameMove(
        localId: Long,
        oldFolder: String,
        oldTitle: String,
        folder: String,
        title: String,
    ): RenameResult {
        if (folder == oldFolder && title == oldTitle) return RenameResult.Unchanged
        val check = creator.check(folder, title, checkTimeoutMs)
        // A check that only sees the Note itself is no clash.
        if (check is NoteCreator.Check.Exists && check.localId != localId) return RenameResult.Clash(folder)
        if (notes.renameMove(title, folder, clock(), localId).value == 0L) {
            // The row changed during the check: a pull made it read-only, or it was deleted.
            val row = notes.selectByLocalId(localId).executeAsOneOrNull()
            return if (row != null && row.sync_state != DELETED && row.readonly) RenameResult.ReadOnly else RenameResult.Gone
        }
        afterEdit()
        return RenameResult.Done
    }

    private companion object {
        const val DELETED = "DELETED"
        const val SYNCED = "SYNCED"
    }
}
