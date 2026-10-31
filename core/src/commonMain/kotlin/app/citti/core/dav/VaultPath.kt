package app.citti.core.dav

/**
 * A path inside the Vault (the Nextcloud Notes folder): no leading `/`, `.` and `..` resolved, never escaping the
 * Vault root. Build it with [parse] or [resolve], which return null for a path that leaves the root.
 */
@kotlin.jvm.JvmInline
value class VaultPath private constructor(
    val value: String,
) {
    val name: String get() = value.substringAfterLast('/')

    val folder: String get() = value.substringBeforeLast('/', "")

    override fun toString() = value

    companion object {
        /** [path] from the Vault root, as is (no percent decoding). */
        fun parse(path: String): VaultPath? = join("", path, decode = false)

        /** A link written in a Note of [folder]: percent escapes decoded first, then joined, `.` and `..` resolved. */
        fun resolve(
            folder: String,
            relative: String,
        ): VaultPath? = join(if (relative.startsWith("/")) "" else folder, relative, decode = true)

        private fun join(
            folder: String,
            relative: String,
            decode: Boolean,
        ): VaultPath? {
            val link = if (decode) percentDecode(relative) else relative
            val parts = ArrayList<String>()
            for (segment in (folder + "/" + link).split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1)
                    else -> parts += segment
                }
            }
            return if (parts.isEmpty()) null else VaultPath(parts.joinToString("/"))
        }
    }
}

/** Decodes `%20`-style escapes as UTF-8; a malformed escape is kept as typed. */
internal fun percentDecode(s: String): String {
    if (s.indexOf('%') < 0) return s
    val out = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        val byte = if (c == '%' && i + 3 <= s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
        if (byte != null) {
            out += byte.toByte()
            i += 3
        } else {
            out.addAll(c.toString().encodeToByteArray().toList())
            i++
        }
    }
    return out.toByteArray().decodeToString()
}

/** Percent-encodes one path segment (UTF-8); only unreserved characters stay as they are. */
internal fun encodeSegment(s: String): String {
    val sb = StringBuilder()
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        val plain =
            c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
                c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
        if (plain) sb.append(c.toChar()) else sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
    }
    return sb.toString()
}
