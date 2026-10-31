package app.citti.core.dav

import app.citti.core.api.ApiError
import app.citti.core.api.ApiResult
import app.citti.core.settings.ImageSettings

sealed interface UploadResult {
    /**
     * The file is at [path]; [link] is the markdown image link to insert (`![](media/a%20b.jpg)`). [etag] is the
     * server's, if it sent one, for seeding the Image cache.
     */
    data class Uploaded(
        val path: VaultPath,
        val link: String,
        val etag: String? = null,
    ) : UploadResult

    data class Failed(
        val error: ApiError,
    ) : UploadResult
}

/** Tries `name.ext`, then `name-2.ext` up to this suffix, while the server answers that the file exists. */
const val MAX_UPLOAD_SUFFIX = 20

private const val FALLBACK_NAME = "image"

/**
 * Uploads an Image into the Vault over WebDAV, as is (no resizing), and gives the link to insert. The folder is
 * `images.upload-folder` when set, else the attachment folder of Obsidian (`media` by default). A file is never
 * overwritten: a taken name gets `-2`, `-3`… before the extension.
 */
class ImageUploader(
    private val dav: DavClient,
    private val config: ObsidianConfig,
    private val settings: ImageSettings,
    private val resolver: ImageResolver,
) {
    /**
     * Uploads [bytes] as [name] (sanitised). [noteFolder] is the folder of the Note the link goes into; it is only
     * used when Obsidian keeps attachments next to the Note (`./`).
     */
    suspend fun upload(
        bytes: ByteArray,
        name: String,
        contentType: String,
        noteFolder: String = "",
    ): UploadResult {
        val attachments = attachmentFolder(noteFolder)
        val folder = settings.uploadFolder() ?: attachments
        val clean = sanitiseName(name)
        for (n in 1..MAX_UPLOAD_SUFFIX) {
            // The name has no `/` and is not `.` or `..`, and the folder is a Vault path: this always parses.
            val path = checkNotNull(VaultPath.parse(join(folder, withSuffix(clean, n))))
            when (val result = dav.put(path, bytes, contentType)) {
                is ApiResult.Failure -> {
                    return UploadResult.Failed(result.error)
                }

                is ApiResult.Success -> {
                    when (result.value) {
                        DavPut.Exists -> {
                            continue
                        }

                        is DavPut.Created -> {
                            val target = linkTarget(path, attachments)
                            resolver.rememberUpload(target, path)
                            return UploadResult.Uploaded(path, "![]($target)", result.value.etag)
                        }
                    }
                }
            }
        }
        return UploadResult.Failed(ApiError.Http(412))
    }

    /** The attachment folder as a Vault path (`""` for the root), with `./` read as the Note's folder. */
    private suspend fun attachmentFolder(noteFolder: String): String {
        val raw = config.attachmentFolder() ?: return noteFolder
        val folder = if (raw.startsWith("./")) join(noteFolder, raw.removePrefix("./")) else raw
        return VaultPath.parse(folder)?.value ?: ""
    }

    companion object {
        private val FORBIDDEN = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        /** `/ \ : * ? " < > |` and control characters become `_`; an empty, `.` or `..` name becomes `image`. */
        fun sanitiseName(name: String): String {
            val clean = name.map { if (it in FORBIDDEN || it.isISOControl()) '_' else it }.joinToString("").trim()
            return if (clean.isEmpty() || clean.all { it == '.' }) FALLBACK_NAME else clean
        }

        /** `a.jpg` → `a-2.jpg` for [n] = 2; [n] = 1 keeps the name. */
        internal fun withSuffix(
            name: String,
            n: Int,
        ): String {
            if (n == 1) return name
            val dot = name.lastIndexOf('.')
            return if (dot > 0) name.substring(0, dot) + "-$n" + name.substring(dot) else "$name-$n"
        }

        /**
         * What goes between the parentheses of the link to [path]: relative to [attachments] when the file is inside
         * it (Obsidian's convention), else the Vault path. Spaces, `(`, `)`, `<`, `>` and `%` are percent-encoded.
         */
        fun linkTarget(
            path: VaultPath,
            attachments: String,
        ): String {
            val target =
                if (attachments.isNotEmpty() && path.value.startsWith("$attachments/")) {
                    path.value.removePrefix("$attachments/")
                } else {
                    path.value
                }
            return encodeLink(target)
        }

        internal fun encodeLink(s: String): String =
            buildString {
                for (c in s) {
                    when (c) {
                        '%' -> append("%25")
                        ' ' -> append("%20")
                        '(' -> append("%28")
                        ')' -> append("%29")
                        '<' -> append("%3C")
                        '>' -> append("%3E")
                        else -> append(c)
                    }
                }
            }

        private fun join(
            folder: String,
            name: String,
        ) = if (folder.isEmpty()) name else "$folder/$name"
    }
}
