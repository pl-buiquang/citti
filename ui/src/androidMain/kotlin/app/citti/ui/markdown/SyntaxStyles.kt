package app.citti.ui.markdown

import android.graphics.Typeface
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import app.citti.core.markdown.Span
import app.citti.core.markdown.SpanKind

/** Marks the spans the highlighter put on the text, so it can remove only those and leave the IME's alone. */
internal interface SyntaxSpan

private class Fg(
    color: Int,
) : ForegroundColorSpan(color),
    SyntaxSpan

private class Bold :
    StyleSpan(Typeface.BOLD),
    SyntaxSpan

private class Strike :
    StrikethroughSpan(),
    SyntaxSpan

private class Face(
    face: Typeface,
) : TypefaceSpan(face),
    SyntaxSpan

private class Bg(
    color: Int,
) : BackgroundColorSpan(color),
    SyntaxSpan

/** The theme tokens of Edit mode (spec §3 "Edit mode styling") as ARGB ints and typefaces. */
internal class SyntaxStyles(
    val ink: Int,
    val inkMuted: Int,
    val inkFaint: Int,
    val accentInk: Int,
    val highlight: Int,
    val mono: Typeface,
) {
    /** The spans for one lexer [span], in the order they must be applied. */
    fun spansFor(span: Span): List<CharacterStyle> =
        when (span.kind) {
            SpanKind.HeadingText -> {
                listOf(Fg(ink), Bold())
            }

            SpanKind.HeadingMark, SpanKind.Mark, SpanKind.ListMark, SpanKind.TaskMark, SpanKind.Quote,
            SpanKind.WikilinkMark, SpanKind.ImageMark, SpanKind.LinkTarget, SpanKind.Url,
            -> {
                listOf(Fg(inkFaint))
            }

            SpanKind.Bold -> {
                listOf(Bold())
            }

            SpanKind.Italic -> {
                listOf(ItalicSyntax())
            }

            SpanKind.Strike -> {
                listOf(Strike())
            }

            SpanKind.Highlight -> {
                listOf(Bg(highlight))
            }

            SpanKind.InlineCode, SpanKind.CodeFence, SpanKind.CodeBlock, SpanKind.Frontmatter -> {
                listOf(Fg(inkFaint), Face(mono))
            }

            SpanKind.TaskDone -> {
                listOf(Fg(inkMuted))
            }

            SpanKind.WikilinkTarget, SpanKind.WikilinkAlias, SpanKind.LinkText -> {
                listOf(Fg(accentInk))
            }
        }
}

private class ItalicSyntax :
    StyleSpan(Typeface.ITALIC),
    SyntaxSpan
