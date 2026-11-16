package app.citti.core.dav

/** One element of a parsed XML document; [ns] is its namespace URI ("" when none). */
class XmlElement(
    val ns: String,
    val name: String,
    val attributes: Map<String, String>,
    val children: List<XmlElement>,
    /** The element's own text and CDATA, children's excluded. */
    val text: String,
) {
    fun child(
        ns: String,
        name: String,
    ): XmlElement? = children.firstOrNull { it.ns == ns && it.name == name }

    fun children(
        ns: String,
        name: String,
    ): List<XmlElement> = children.filter { it.ns == ns && it.name == name }

    /** Every descendant (and this element) with that name, depth first. */
    fun descendants(
        ns: String,
        name: String,
    ): List<XmlElement> {
        val out = mutableListOf<XmlElement>()

        fun walk(e: XmlElement) {
            if (e.ns == ns && e.name == name) out += e
            e.children.forEach(::walk)
        }
        walk(this)
        return out
    }
}

class XmlException(
    message: String,
) : Exception(message)

/**
 * A small namespace-aware XML reader for WebDAV multistatus bodies. It refuses a DOCTYPE, so no external entity
 * is fetched and no entity expands (ADR 0012); only the five predefined entities and character references are read.
 */
object Xml {
    fun parse(text: String): XmlElement = Parser(text).document()

    private class Frame(
        val prefixes: Map<String, String>,
        val qname: String,
        val attributes: Map<String, String>,
    ) {
        val children = mutableListOf<XmlElement>()
        val text = StringBuilder()
    }

    private class Parser(
        val s: String,
    ) {
        var i = 0

        fun document(): XmlElement {
            val stack = ArrayDeque<Frame>()
            var root: XmlElement? = null
            while (i < s.length) {
                if (s.startsWith("<?", i)) {
                    skipPast("?>")
                } else if (s.startsWith("<!--", i)) {
                    skipPast("-->")
                } else if (s.startsWith("<![CDATA[", i)) {
                    val end = s.indexOf("]]>", i).takeIf { it >= 0 } ?: throw XmlException("unclosed CDATA")
                    stack.lastOrNull()?.text?.append(s, i + 9, end)
                    i = end + 3
                } else if (s.startsWith("<!", i)) {
                    throw XmlException("DOCTYPE and declarations are not accepted")
                } else if (s.startsWith("</", i)) {
                    val end = s.indexOf('>', i).takeIf { it >= 0 } ?: throw XmlException("unclosed end tag")
                    val qname = s.substring(i + 2, end).trim()
                    i = end + 1
                    val frame = stack.removeLastOrNull() ?: throw XmlException("unexpected </$qname>")
                    if (frame.qname != qname) throw XmlException("</$qname> closes <${frame.qname}>")
                    val element = build(frame)
                    if (stack.isEmpty()) root = element else stack.last().children += element
                } else if (s[i] == '<') {
                    val (frame, selfClosing) = startTag(stack.lastOrNull()?.prefixes ?: DEFAULT_PREFIXES)
                    if (root != null && stack.isEmpty()) throw XmlException("more than one root element")
                    if (selfClosing) {
                        val element = build(frame)
                        if (stack.isEmpty()) root = element else stack.last().children += element
                    } else {
                        stack.addLast(frame)
                    }
                } else {
                    val end = s.indexOf('<', i).let { if (it < 0) s.length else it }
                    stack.lastOrNull()?.text?.append(decode(s.substring(i, end)))
                    i = end
                }
            }
            if (stack.isNotEmpty()) throw XmlException("unclosed <${stack.last().qname}>")
            return root ?: throw XmlException("no root element")
        }

        private fun skipPast(end: String) {
            val at = s.indexOf(end, i)
            if (at < 0) throw XmlException("unclosed $end")
            i = at + end.length
        }

        private fun startTag(parent: Map<String, String>): Pair<Frame, Boolean> {
            i++ // <
            val nameEnd = indexOfAny(" \t\r\n/>")
            val qname = s.substring(i, nameEnd)
            if (qname.isEmpty()) throw XmlException("empty tag name")
            i = nameEnd
            val raw = mutableMapOf<String, String>()
            while (true) {
                while (i < s.length && s[i].isWhitespace()) i++
                if (i >= s.length) throw XmlException("unclosed <$qname")
                if (s.startsWith("/>", i)) {
                    i += 2
                    return frame(parent, qname, raw) to true
                }
                if (s[i] == '>') {
                    i++
                    return frame(parent, qname, raw) to false
                }
                val eq = s.indexOf('=', i).takeIf { it >= 0 } ?: throw XmlException("attribute without value in <$qname")
                val key = s.substring(i, eq).trim()
                i = eq + 1
                while (i < s.length && s[i].isWhitespace()) i++
                val quote = s.getOrNull(i)?.takeIf { it == '"' || it == '\'' } ?: throw XmlException("unquoted attribute in <$qname")
                val close = s.indexOf(quote, i + 1).takeIf { it >= 0 } ?: throw XmlException("unclosed attribute in <$qname")
                raw[key] = decode(s.substring(i + 1, close))
                i = close + 1
            }
        }

        private fun frame(
            parent: Map<String, String>,
            qname: String,
            raw: Map<String, String>,
        ): Frame {
            val prefixes = parent.toMutableMap()
            raw.forEach { (k, v) ->
                when {
                    k == "xmlns" -> prefixes[""] = v
                    k.startsWith("xmlns:") -> prefixes[k.removePrefix("xmlns:")] = v
                }
            }
            val attributes = raw.filterKeys { it != "xmlns" && !it.startsWith("xmlns:") }.mapKeys { it.key.substringAfter(':') }
            return Frame(prefixes, qname, attributes)
        }

        private fun build(frame: Frame): XmlElement {
            val prefix = if (':' in frame.qname) frame.qname.substringBefore(':') else ""
            val ns = frame.prefixes[prefix] ?: if (prefix.isEmpty()) "" else throw XmlException("unknown prefix $prefix")
            return XmlElement(ns, frame.qname.substringAfter(':'), frame.attributes, frame.children.toList(), frame.text.toString())
        }

        private fun indexOfAny(chars: String): Int {
            var j = i
            while (j < s.length && s[j] !in chars) j++
            return j
        }

        private fun decode(text: String): String {
            if ('&' !in text) return text
            val out = StringBuilder(text.length)
            var j = 0
            while (j < text.length) {
                val c = text[j]
                if (c != '&') {
                    out.append(c)
                    j++
                    continue
                }
                val semi = text.indexOf(';', j).takeIf { it > j } ?: throw XmlException("bad entity")
                val entity = text.substring(j + 1, semi)
                when {
                    entity == "amp" -> out.append('&')
                    entity == "lt" -> out.append('<')
                    entity == "gt" -> out.append('>')
                    entity == "quot" -> out.append('"')
                    entity == "apos" -> out.append('\'')
                    entity.startsWith("#x") -> out.appendCodePoint(entity.substring(2).toIntOrNull(16))
                    entity.startsWith("#") -> out.appendCodePoint(entity.substring(1).toIntOrNull())
                    else -> throw XmlException("unknown entity &$entity;")
                }
                j = semi + 1
            }
            return out.toString()
        }

        private fun StringBuilder.appendCodePoint(cp: Int?) {
            if (cp == null || cp !in 0..0x10FFFF) throw XmlException("bad character reference")
            if (cp < 0x10000) {
                append(cp.toChar())
            } else {
                val v = cp - 0x10000
                append((0xD800 + (v shr 10)).toChar())
                append((0xDC00 + (v and 0x3FF)).toChar())
            }
        }
    }

    private val DEFAULT_PREFIXES = mapOf("xml" to "http://www.w3.org/XML/1998/namespace")
}
