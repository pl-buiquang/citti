package app.citti.core.daily

import app.citti.core.api.ApiError
import app.citti.core.api.ApiResult
import app.citti.core.api.ListResult
import app.citti.core.api.NotesApi
import app.citti.core.dav.DavClient
import app.citti.core.dav.DavGet
import app.citti.core.dav.VaultPath
import app.citti.core.db.CittiDatabase
import app.citti.core.log.SyncLog
import app.citti.core.note.CREATION_CHECK_MS
import app.citti.core.settings.DailySettings
import app.citti.core.settings.TemplateCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/** The text a Daily note is rendered from. */
sealed interface TemplateText {
    data class Found(
        val text: String,
    ) : TemplateText

    /** No Template is set (the path is `""`). */
    data object None : TemplateText

    /** The Template at [path] was found nowhere: the note starts empty and says so. */
    data class Missing(
        val path: String,
    ) : TemplateText
}

/**
 * Finds the Template text of a Daily note: (1) the local Note `(folder, title)` of the path, its text fetched within
 * the cap when it isn't local yet; (2) the cached text of `daily.template-cache` for that path, used at once and
 * revalidated in [appScope]; (3) online, within the cap, the Notes API (`listNotes(category)` then `getNote`), else a
 * WebDAV `GET` of the path, cached; (4) else [TemplateText.Missing].
 *
 * The online lookup is a plain call: it doesn't go through the Folder refresh and stores no Note, so a Template in an
 * Unsynced folder keeps that folder Unsynced; only the Template text is cached. Blocking database reads: call it off
 * the main thread.
 */
class TemplateSource(
    db: CittiDatabase,
    private val api: NotesApi,
    private val dav: DavClient,
    private val settings: DailySettings,
    /** `SyncEngine.fetchContent`: downloads the text of a SYNCED Note that isn't local yet. */
    private val fetchContent: suspend (Long) -> Boolean,
    private val appScope: CoroutineScope,
    private val log: SyncLog = SyncLog.None,
    /** Measures the [load] cap; a test passes its virtual time. */
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val notes = db.noteQueries

    /**
     * The Template at [path] (a Vault path with `.md`, `""` for none). [timeoutMs] caps the whole lookup: what the
     * content fetch uses is gone for the online lookup, and with no time left it goes from the cache to Missing.
     */
    suspend fun load(
        path: String,
        timeoutMs: Long = CREATION_CHECK_MS,
    ): TemplateText {
        if (path.isEmpty()) return TemplateText.None
        val start = timeSource.markNow()
        val (folder, title) = noteOf(path)
        notes.selectExactByFolderAndTitle(folder, title).executeAsOneOrNull()?.let { localId ->
            localText(localId)?.let { return TemplateText.Found(it) }
            val fetch = appScope.async { fetchContent(localId) }
            withTimeoutOrNull(timeoutMs) { fetch.await() }
            localText(localId)?.let { return TemplateText.Found(it) }
        }
        settings.templateCache()?.takeIf { it.path == path }?.let { cache ->
            appScope.launch { revalidate(cache) }
            return TemplateText.Found(cache.text)
        }
        // In appScope, so a fetch past the cap still fills the cache for the next creation.
        val left = (timeoutMs - start.elapsedNow().inWholeMilliseconds).coerceAtLeast(0)
        val fetch = appScope.async { fetchRemote(path, null)?.also(::store) }
        val fetched = if (left > 0) withTimeoutOrNull(left) { fetch.await() } else null
        return when (fetched) {
            is Remote.Text -> TemplateText.Found(fetched.cache.text)
            else -> TemplateText.Missing(path)
        }
    }

    private fun localText(localId: Long): String? = notes.selectByLocalId(localId).executeAsOneOrNull()?.content

    /** Fetches the text again and replaces or drops the cache; the next creation gets the fresh text. */
    private suspend fun revalidate(cache: TemplateCache) {
        when (val remote = fetchRemote(cache.path, cache)) {
            is Remote.Text -> store(remote)
            Remote.Gone -> if (settings.templateCache() == cache) settings.clearTemplateCache()
            Remote.Unchanged, null -> Unit
        }
    }

    private fun store(remote: Remote) {
        if (remote !is Remote.Text) return
        // The Template may have changed meanwhile: only cache what is still the Template.
        if (settings.template() == remote.cache.path) settings.setTemplateCache(remote.cache)
    }

    /**
     * The Template at [path] on the server: the Notes API first, then WebDAV. With [cached], an unchanged text gives
     * [Remote.Unchanged]. Null when the server couldn't say (offline, a server error).
     */
    private suspend fun fetchRemote(
        path: String,
        cached: TemplateCache?,
    ): Remote? {
        val (folder, title) = noteOf(path)
        when (val list = api.listNotes(excludeContent = true, category = folder)) {
            is ApiResult.Failure -> {
                log.warn("template list failed path=$path")
                if (list.error is ApiError.Network) return null
            }

            is ApiResult.Success -> {
                val page = list.value as? ListResult.Page
                val match = page?.notes?.firstOrNull { it.title == title && it.category == folder }
                if (match != null) {
                    if (cached != null && match.etag != null && match.etag == cached.etag) return Remote.Unchanged
                    return when (val note = api.getNote(match.id)) {
                        is ApiResult.Failure -> null
                        is ApiResult.Success -> note.value.content?.let { Remote.Text(TemplateCache(path, note.value.etag, it)) }
                    }
                }
            }
        }
        val vaultPath = VaultPath.parse(path) ?: return Remote.Gone
        return when (val got = dav.get(vaultPath, ifNoneMatch = cached?.etag)) {
            is ApiResult.Failure -> {
                if (got.error == ApiError.NotFound) Remote.Gone else null
            }

            is ApiResult.Success -> {
                when (val body = got.value) {
                    DavGet.NotModified -> Remote.Unchanged
                    is DavGet.Body -> Remote.Text(TemplateCache(path, body.etag, body.bytes.decodeToString()))
                }
            }
        }
    }

    private sealed interface Remote {
        data class Text(
            val cache: TemplateCache,
        ) : Remote

        data object Unchanged : Remote

        /** The server answered and has no such file. */
        data object Gone : Remote
    }

    private companion object {
        /** The `(folder, title)` of the Note at [path]: the Folder, and the file name without `.md`. */
        fun noteOf(path: String): Pair<String, String> {
            val name = path.removeSuffix(".md")
            return name.substringBeforeLast('/', "") to name.substringAfterLast('/')
        }
    }
}
