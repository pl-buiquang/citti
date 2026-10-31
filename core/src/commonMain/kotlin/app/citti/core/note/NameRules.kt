package app.citti.core.note

import app.citti.core.settings.ExclusionKind
import app.citti.core.settings.ExclusionRepository
import app.citti.core.sync.categoryMatchesAny

/** Why a Note title or a Folder path is refused. */
enum class NameError {
    /** Empty or only whitespace. */
    Blank,

    /** Contains `/` or `\`: a title is one path segment. */
    Slash,

    /** Contains one of `: * ? " < > |` or a control character, unusable from Windows sync clients. */
    UnsafeCharacter,

    /** Starts with `.`: a dot file, which the server and Obsidian skip. */
    LeadingDot,

    /** A Folder segment is `.` or `..`. */
    DotSegment,

    /** The Folder lies under an Unsynced folder. */
    Unsynced,
}

/** A refused name, as the failure of [NameRules.normaliseFolder]. */
class InvalidNameException(
    val error: NameError,
) : IllegalArgumentException("invalid name: $error")

/** The rules that Note titles and Folder paths follow, for creation, Rename and Move. */
object NameRules {
    private const val UNSAFE = ":*?\"<>|"

    /** Null when [title] can be a Note title, else the first rule it breaks. */
    fun validateTitle(title: String): NameError? =
        when {
            title.isBlank() -> NameError.Blank
            title.any { it == '/' || it == '\\' } -> NameError.Slash
            title.any(::isUnsafe) -> NameError.UnsafeCharacter
            title.startsWith('.') -> NameError.LeadingDot
            else -> null
        }

    /** [normaliseFolder] with the Unsynced folders of [exclusions]. */
    fun normaliseFolder(
        path: String,
        exclusions: ExclusionRepository,
    ): Result<String> = normaliseFolder(path, exclusions.list(ExclusionKind.UNSYNCED))

    /**
     * Trims [path], drops empty segments and leading or trailing `/` (`/a//b/` is `a/b`, blank is the Vault root
     * `""`). Refuses a `.` or `..` segment, a segment with `\`, one of `: * ? " < > |` or a control character, and a
     * path under one of the [unsynced] prefixes. The failure is an [InvalidNameException].
     */
    fun normaliseFolder(
        path: String,
        unsynced: Collection<String>,
    ): Result<String> {
        val segments = path.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        val error =
            segments.firstNotNullOfOrNull { segment ->
                when {
                    segment == "." || segment == ".." -> NameError.DotSegment
                    '\\' in segment -> NameError.Slash
                    segment.any(::isUnsafe) -> NameError.UnsafeCharacter
                    else -> null
                }
            }
        if (error != null) return Result.failure(InvalidNameException(error))
        val folder = segments.joinToString("/")
        if (folder.isNotEmpty() && categoryMatchesAny(folder, unsynced)) {
            return Result.failure(InvalidNameException(NameError.Unsynced))
        }
        return Result.success(folder)
    }

    private fun isUnsafe(c: Char) = c in UNSAFE || c.isISOControl()
}
