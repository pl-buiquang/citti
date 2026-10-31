package app.citti.core.daily

import app.citti.core.settings.DailySettings
import app.citti.core.settings.ExclusionKind
import app.citti.core.settings.ExclusionRepository
import app.citti.core.sync.categoryMatchesAny
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Notices a Journal folder that lies under an Unsynced folder, a state saved before M6 (both setters refuse it now).
 * While it holds, Daily notes create nothing and Settings shows a banner.
 */
class DailyGuard(
    private val settings: DailySettings,
    private val exclusions: ExclusionRepository,
) {
    /** The Journal folder when it lies under an Unsynced folder, else null; follows both settings. */
    fun conflict(): Flow<String?> =
        combine(settings.folderFlow(), exclusions.flow(ExclusionKind.UNSYNCED), ::conflictOf).distinctUntilChanged()

    /** [conflict] now. */
    fun current(): String? = conflictOf(settings.folder(), exclusions.list(ExclusionKind.UNSYNCED))

    private fun conflictOf(
        journal: String,
        unsynced: List<String>,
    ): String? = journal.takeIf { it.isNotEmpty() && categoryMatchesAny(it, unsynced) }
}
