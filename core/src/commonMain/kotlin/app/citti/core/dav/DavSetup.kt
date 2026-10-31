package app.citti.core.dav

import app.citti.core.api.ApiResult
import app.citti.core.api.NotesApi
import app.citti.core.db.CittiDatabase

const val KEY_DAV_USER_ID = "dav.user-id"
const val KEY_DAV_NOTES_PATH = "dav.notes-path"

/**
 * Finds and remembers what WebDAV URLs need: the user id (`ocs/v2.php/cloud/user`, can differ from the login
 * name) and the Notes folder (`/settings`). Both live in `setting`, which sign-out wipes. A failed request keeps
 * the stored value.
 */
class DavSetup(
    private val api: NotesApi,
    db: CittiDatabase,
    private val baseUrl: String,
) {
    private val settings = db.settingQueries

    /** The stored root, or null until a refresh succeeded once. */
    fun root(): DavRoot? {
        val user = settings.selectSetting(KEY_DAV_USER_ID).executeAsOneOrNull() ?: return null
        val notes = settings.selectSetting(KEY_DAV_NOTES_PATH).executeAsOneOrNull() ?: return null
        return DavRoot(baseUrl, user, notes)
    }

    val isStored: Boolean get() = root() != null

    /** Two cheap requests; call on app open, or when nothing is stored yet. */
    suspend fun refresh() {
        (api.userId() as? ApiResult.Success)?.let { settings.upsertSetting(KEY_DAV_USER_ID, it.value) }
        (api.settings() as? ApiResult.Success)?.let { settings.upsertSetting(KEY_DAV_NOTES_PATH, it.value.notesPath) }
    }
}
