package app.citti.ui.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import app.citti.core.markdown.Inline
import app.citti.core.note.LinkTarget
import app.citti.ui.note.LinkRef
import app.citti.ui.theme.NotesColors

/**
 * How inline runs become an [AnnotatedString]: the theme [colors], what each Note link [links] points at (not yet
 * resolved counts as resolved), and what a tap on a Note link does.
 */
internal class InlineStyles(
    val colors: NotesColors,
    val links: Map<LinkRef, LinkTarget>,
    val onLink: (LinkRef) -> Unit,
)

internal fun List<Inline>.toAnnotated(styles: InlineStyles): AnnotatedString =
    buildAnnotatedString {
        appendInlines(this@toAnnotated, styles)
    }

private fun AnnotatedString.Builder.appendInlines(
    inlines: List<Inline>,
    styles: InlineStyles,
) {
    val colors = styles.colors
    for (inline in inlines) {
        when (inline) {
            is Inline.Text -> {
                append(inline.text)
            }

            is Inline.Bold -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInlines(inline.children, styles) }
            }

            is Inline.Italic -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInlines(inline.children, styles) }
            }

            is Inline.Strike -> {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInlines(inline.children, styles) }
            }

            is Inline.Highlight -> {
                withStyle(SpanStyle(background = colors.highlight, color = colors.onHighlight)) { appendInlines(inline.children, styles) }
            }

            is Inline.Code -> {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.surfaceRaised)) { append(inline.text) }
            }

            is Inline.Link -> {
                val link = SpanStyle(color = colors.accentInk, textDecoration = TextDecoration.Underline)
                if (isOpenableUrl(inline.url)) {
                    withLink(LinkAnnotation.Url(inline.url, TextLinkStyles(link))) { appendInlines(inline.children, styles) }
                } else {
                    appendInlines(inline.children, styles)
                }
            }

            is Inline.NoteLink -> {
                val ref = LinkRef.Markdown(inline.path)
                noteLink(ref, styles) { appendInlines(inline.children, styles) }
            }

            is Inline.Wikilink -> {
                val ref = LinkRef.Wiki(inline.target)
                noteLink(ref, styles) { append(inline.alias ?: inline.target.substringBefore('#').substringAfterLast('/')) }
            }

            // Images are drawn as their own blocks; one inside a line of text shows its alt text.
            is Inline.Image -> {
                withStyle(SpanStyle(color = colors.inkMuted)) { append(inline.alt.ifEmpty { inline.src.substringAfterLast('/') }) }
            }

            is Inline.Embed -> {
                withStyle(SpanStyle(color = colors.inkMuted)) { append(inline.name) }
            }

            Inline.LineBreak -> {
                append('\n')
            }
        }
    }
}

private val OPENABLE_SCHEMES = setOf("http", "https", "mailto", "tel")

/** Whether a tap on the link [url] may hand it to the system: only web, mail and phone links. Any other text stays inert. */
internal fun isOpenableUrl(url: String): Boolean = url.substringBefore(':', "").lowercase() in OPENABLE_SCHEMES

private inline fun AnnotatedString.Builder.noteLink(
    ref: LinkRef,
    styles: InlineStyles,
    content: AnnotatedString.Builder.() -> Unit,
) {
    val colors = styles.colors
    val style =
        when (styles.links[ref]) {
            null, is LinkTarget.Note -> SpanStyle(color = colors.accentInk, textDecoration = TextDecoration.Underline)

            // Not found: muted. Not synced on this device: muted too, with a mark.
            LinkTarget.Missing -> SpanStyle(color = colors.inkMuted, textDecoration = TextDecoration.Underline)

            is LinkTarget.Unsynced -> SpanStyle(color = colors.inkMuted)
        }
    val listener = LinkInteractionListener { styles.onLink(ref) }
    withLink(LinkAnnotation.Clickable(ref.toString(), TextLinkStyles(style), listener)) { content() }
    if (styles.links[ref] is LinkTarget.Unsynced) withStyle(SpanStyle(color = colors.inkMuted)) { append(UNSYNCED_MARK) }
}

private const val UNSYNCED_MARK = " ⊘"
