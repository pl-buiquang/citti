package app.citti.core.dav

import app.citti.core.api.ApiResult
import app.citti.core.db.CittiDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

const val KEY_OBSIDIAN_ATTACHMENTS = "obsidian.attachments"
const val DEFAULT_ATTACHMENTS = "media"

/**
 * The attachment folder of the Vault, from `.obsidian/app.json` (`attachmentFolderPath`). Read once per session
 * and kept in `setting`, so Images resolve offline. Missing file or key: `media`. `./` (same folder as the Note)
 * gives null: the first image candidate already covers it.
 */
class ObsidianConfig(
    private val dav: DavClient,
    db: CittiDatabase,
) {
    private val settings = db.settingQueries
    private var loaded = false
    private var folder: String? = settings.selectSetting(KEY_OBSIDIAN_ATTACHMENTS).executeAsOneOrNull() ?: DEFAULT_ATTACHMENTS

    /** The attachment folder kept from an earlier read, with no request (the default before the first read). */
    fun storedAttachmentFolder(): String? = folder?.takeIf { it.isNotEmpty() && it != "./" && it != "." }

    suspend fun attachmentFolder(): String? {
        if (!loaded) {
            when (val result = dav.get(VaultPath.parse(".obsidian/app.json")!!)) {
                is ApiResult.Success -> {
                    val body = result.value
                    if (body is DavGet.Body) store(parse(body.bytes.decodeToString()))
                    loaded = true
                }

                is ApiResult.Failure -> {
                    // A missing file means the default; any other failure keeps what was stored and retries later.
                    if (result.error == app.citti.core.api.ApiError.NotFound) {
                        store(DEFAULT_ATTACHMENTS)
                        loaded = true
                    }
                }
            }
        }
        return storedAttachmentFolder()
    }

    private fun store(value: String) {
        folder = value
        settings.upsertSetting(KEY_OBSIDIAN_ATTACHMENTS, value)
    }

    private fun parse(json: String): String =
        runCatching {
            (Json.parseToJsonElement(json).jsonObject["attachmentFolderPath"] as? JsonPrimitive)?.contentOrNull
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it == "./" || it == "/") it else it.trim('/') } ?: DEFAULT_ATTACHMENTS
}
