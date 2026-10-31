package app.citti.ui.nav

/** What a `citti://` link asks the app to do. Independent of [Route]: the host builds the route. */
sealed interface DeepLinkTarget {
    /** Open the Note [localId], scrolled to the source line [line] when set. */
    data class OpenNote(
        val localId: Long,
        val line: Int?,
    ) : DeepLinkTarget

    /** Open or create today's Daily note, resolved at the tap; [resume] opens an existing one at its Reading position. */
    data class Today(
        val resume: Boolean,
    ) : DeepLinkTarget
}

/** Parses and builds the `citti://note/<id>[?line=<n>]` and `citti://daily[?resume=1]` links the widgets use. */
object DeepLink {
    private const val SCHEME = "citti://"

    fun noteUri(
        localId: Long,
        line: Int?,
    ): String = "${SCHEME}note/$localId" + if (line != null) "?line=$line" else ""

    fun dailyUri(resume: Boolean): String = "${SCHEME}daily" + if (resume) "?resume=1" else ""

    /** The target of [uri], or null when it is not a link of ours (the app then opens normally). */
    fun parse(uri: String): DeepLinkTarget? {
        if (!uri.startsWith(SCHEME)) return null
        val rest = uri.removePrefix(SCHEME).substringBefore('#')
        val path = rest.substringBefore('?')
        val query = query(rest.substringAfter('?', ""))
        val segments = path.split('/')
        return when (segments.first()) {
            "note" -> {
                if (segments.size != 2) return null
                val id = segments[1].digits()?.toLongOrNull()?.takeIf { it > 0 } ?: return null
                DeepLinkTarget.OpenNote(id, query["line"]?.digits()?.toIntOrNull())
            }

            "daily" -> {
                if (segments.size == 1) DeepLinkTarget.Today(resume = query["resume"] == "1") else null
            }

            else -> {
                null
            }
        }
    }

    private fun String.digits(): String? = takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }

    private fun query(q: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            out.getOrPut(pair.substringBefore('=')) { pair.substringAfter('=', "") }
        }
        return out
    }
}
