package app.citti.ui.markdown

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import app.citti.core.markdown.Align
import app.citti.core.markdown.Block
import app.citti.core.markdown.Doc
import app.citti.core.markdown.Inline
import app.citti.core.markdown.Property
import app.citti.core.markdown.Task
import app.citti.core.markdown.plainText
import app.citti.core.note.LinkTarget
import app.citti.ui.components.colored
import app.citti.ui.image.ImageEnv
import app.citti.ui.image.VaultImage
import app.citti.ui.note.LinkRef
import app.citti.ui.resources.Res
import app.citti.ui.resources.properties
import app.citti.ui.theme.CittiTheme
import app.citti.ui.theme.NotesSize
import app.citti.ui.theme.NotesSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * Preview mode: one lazy item per block, per list item and per chunk of a long code block or paragraph (the parser
 * already split them), so a note of thousands of lines scrolls without building it all. Text is selectable per block.
 * A task item calls [onTask] (inert when [readonly]); a tap on a Note link calls [onLink]; web links open in the
 * browser. [images] loads the Images of the Note. Tokens only: colors, text styles and spacing come from the theme.
 */
@Composable
fun MarkdownPreview(
    doc: Doc,
    links: Map<LinkRef, LinkTarget>,
    listState: LazyListState,
    readonly: Boolean,
    onTask: (Task) -> Unit,
    onLink: (LinkRef) -> Unit,
    modifier: Modifier = Modifier,
    images: ImageEnv? = null,
) {
    val colors = CittiTheme.colors
    val styles = remember(colors, links, onLink) { InlineStyles(colors, links, onLink) }
    val scope = PreviewScope(styles, readonly, onTask, images)
    LazyColumn(modifier.fillMaxSize(), state = listState) {
        itemsIndexed(doc.blocks, key = { index, block -> "$index:${block.start}" }) { _, block ->
            Box(Modifier.fillMaxWidth().padding(horizontal = CittiTheme.shape.gutter)) { scope.Block(block, muted = false) }
        }
    }
}

private class PreviewScope(
    val styles: InlineStyles,
    val readonly: Boolean,
    val onTask: (Task) -> Unit,
    val images: ImageEnv?,
)

private val MONO = FontFamily.Monospace

@Composable
private fun PreviewScope.Block(
    block: Block,
    muted: Boolean,
) {
    when (block) {
        is Block.Properties -> {
            PropertiesBlock(block)
        }

        is Block.Heading -> {
            HeadingBlock(block)
        }

        is Block.Paragraph -> {
            Indented(
                block.depth,
            ) { Text(block.inlines, bodyStyle(muted), Modifier.padding(vertical = NotesSpacing.space2)) }
        }

        is Block.ImageBlock -> {
            Indented(block.depth) {
                Column {
                    block.images.forEach { image -> images?.let { VaultImage(image, it) } ?: ImagePlaceholder(image) }
                }
            }
        }

        is Block.ListItem -> {
            ListItemRow(block, muted)
        }

        is Block.Quote -> {
            QuoteBlock(block)
        }

        is Block.Code -> {
            CodeBlock(block)
        }

        is Block.Table -> {
            TableBlock(block)
        }

        is Block.Rule -> {
            RuleBlock()
        }
    }
}

@Composable
private fun bodyStyle(muted: Boolean): TextStyle = CittiTheme.typography.body.colored(muted)

@Composable
private fun Indented(
    depth: Int,
    content: @Composable () -> Unit,
) {
    Box(Modifier.padding(start = NotesSpacing.space6 * depth)) { content() }
}

@Composable
private fun PreviewScope.Text(
    inlines: List<Inline>,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val text = remember(inlines, styles) { inlines.toAnnotated(styles) }
    SelectionContainer(modifier) { BasicText(text, style = style) }
}

// ---- blocks ----

@Composable
private fun PreviewScope.HeadingBlock(block: Block.Heading) {
    val typography = CittiTheme.typography
    val base =
        when (block.level) {
            1 -> typography.title
            2 -> typography.title.scaled(HEADING_2)
            3 -> typography.rowTitle.scaled(HEADING_3)
            else -> typography.rowTitle
        }
    Text(
        block.inlines,
        base.copy(color = CittiTheme.colors.ink, fontWeight = FontWeight.Bold),
        Modifier.padding(top = NotesSpacing.space4, bottom = NotesSpacing.space2).semantics { heading() },
    )
}

private fun TextStyle.scaled(factor: Float): TextStyle =
    copy(fontSize = fontSize.times(factor), lineHeight = if (lineHeight == TextUnit.Unspecified) lineHeight else lineHeight.times(factor))

@Composable
private fun PreviewScope.ListItemRow(
    item: Block.ListItem,
    muted: Boolean,
) {
    val colors = CittiTheme.colors
    val task = item.task
    val done = task?.checked == true
    val style = bodyStyle(muted || done).let { if (done) it.copy(textDecoration = TextDecoration.LineThrough) else it }
    val toggleable = task != null && !readonly
    val label = if (task != null) item.inlines.plainTextForA11y() else ""
    Row(
        Modifier
            .padding(start = NotesSpacing.space6 * item.depth)
            .fillMaxWidth()
            .heightIn(min = if (task != null) NotesSize.touchMin else Dp.Unspecified)
            .then(
                if (task != null) {
                    Modifier.toggleable(value = done, enabled = toggleable, role = Role.Checkbox) { onTask(task) }
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space2),
    ) {
        Box(Modifier.widthIn(min = NotesSpacing.space5).padding(top = NotesSpacing.space2), contentAlignment = Alignment.TopStart) {
            when {
                task != null -> Checkbox(done, colors.accent, colors.onAccent, colors.inkMuted)
                item.ordered -> BasicText("${item.number ?: 1}.", style = style.copy(color = colors.inkMuted))
                else -> BasicText("•", style = style.copy(color = colors.inkMuted))
            }
        }
        Text(
            item.inlines,
            style,
            Modifier.weight(1f).padding(vertical = NotesSpacing.space2).semantics {
                if (task !=
                    null
                ) {
                    contentDescription = label
                }
            },
        )
    }
}

private fun List<Inline>.plainTextForA11y(): String = plainText()

@Composable
private fun Checkbox(
    checked: Boolean,
    fill: Color,
    mark: Color,
    outline: Color,
) {
    Canvas(Modifier.size(NotesSpacing.space5)) {
        val stroke = size.minDimension / 12f
        if (checked) {
            drawRoundRect(fill, cornerRadius = CornerRadius(stroke * 2))
            val w = size.width
            val h = size.height
            drawLine(mark, Offset(w * 0.25f, h * 0.52f), Offset(w * 0.43f, h * 0.7f), stroke, StrokeCap.Round)
            drawLine(mark, Offset(w * 0.43f, h * 0.7f), Offset(w * 0.76f, h * 0.32f), stroke, StrokeCap.Round)
        } else {
            drawRoundRect(outline, cornerRadius = CornerRadius(stroke * 2), style = Stroke(stroke))
        }
    }
}

@Composable
private fun PreviewScope.QuoteBlock(block: Block.Quote) {
    val colors = CittiTheme.colors
    Row(Modifier.height(IntrinsicSize.Min).padding(vertical = NotesSpacing.space1)) {
        Box(Modifier.width(NotesSpacing.space1).fillMaxHeight().background(colors.line))
        Column(Modifier.padding(start = NotesSpacing.space3)) {
            block.blocks.forEach { Block(it, muted = true) }
        }
    }
}

@Composable
private fun PreviewScope.CodeBlock(block: Block.Code) {
    val colors = CittiTheme.colors
    val shape = RoundedCornerShape(CittiTheme.shape.control)
    Indented(block.depth) {
        Box(
            Modifier
                .then(if (block.continued) Modifier else Modifier.padding(top = NotesSpacing.space2))
                .fillMaxWidth()
                .background(colors.surfaceRaised, shape)
                .horizontalScroll(rememberScrollState())
                .padding(NotesSpacing.space3),
        ) {
            SelectionContainer {
                BasicText(
                    block.text,
                    softWrap = false,
                    style = CittiTheme.typography.editor.copy(fontFamily = MONO, color = colors.ink),
                )
            }
        }
    }
}

@Composable
private fun PreviewScope.TableBlock(block: Block.Table) {
    val colors = CittiTheme.colors
    val columns = block.header.size
    val body = CittiTheme.typography.body
    Box(
        Modifier
            .padding(vertical = NotesSpacing.space2)
            .fillMaxWidth()
            .border(Dp.Hairline, colors.line)
            .horizontalScroll(rememberScrollState()),
    ) {
        Column {
            val rows = listOf(block.header) + block.rows
            rows.forEachIndexed { r, row ->
                Row(Modifier.then(if (r == 0) Modifier.background(colors.surface) else Modifier)) {
                    for (c in 0 until columns) {
                        val align =
                            when (block.align.getOrNull(c)) {
                                Align.Center -> TextAlign.Center
                                Align.Right -> TextAlign.End
                                else -> TextAlign.Start
                            }
                        val style = body.colored().copy(textAlign = align, fontWeight = if (r == 0) FontWeight.Bold else body.fontWeight)
                        Box(
                            Modifier
                                .width(NotesSize.touchMin * CELL_WIDTHS)
                                .border(Dp.Hairline, colors.line)
                                .padding(NotesSpacing.space2),
                        ) {
                            Text(row.getOrNull(c).orEmpty(), style, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleBlock() {
    val line = CittiTheme.colors.line
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = NotesSpacing.space4)
            .height(Dp.Hairline)
            .background(line),
    )
}

/** The file name in a bordered box, where Images cannot be loaded (no [ImageEnv]: signed out). */
@Composable
private fun ImagePlaceholder(image: Inline.Image) {
    val colors = CittiTheme.colors
    val name = image.src.substringAfterLast('/').substringBefore('?')
    Box(
        Modifier
            .padding(vertical = NotesSpacing.space2)
            .fillMaxWidth()
            .border(Dp.Hairline, colors.line, RoundedCornerShape(CittiTheme.shape.media))
            .padding(NotesSpacing.space3)
            .semantics { contentDescription = image.alt.ifEmpty { name } },
    ) {
        BasicText(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = CittiTheme.typography.meta.colored(muted = true))
    }
}

@Composable
private fun PropertiesBlock(block: Block.Properties) {
    var open by rememberSaveable { mutableStateOf(false) }
    val colors = CittiTheme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = NotesSpacing.space2)) {
        Box(
            Modifier
                .heightIn(min = NotesSize.touchMin)
                .clickable(role = Role.Button) { open = !open },
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicText(
                (if (open) "▾ " else "▸ ") + stringResource(Res.string.properties),
                style = CittiTheme.typography.label.colored(muted = true),
            )
        }
        if (open) {
            SelectionContainer {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            colors.surfaceRaised,
                            RoundedCornerShape(CittiTheme.shape.control),
                        ).padding(NotesSpacing.space3),
                    verticalArrangement = Arrangement.spacedBy(NotesSpacing.space1),
                ) {
                    val entries = block.entries
                    if (entries == null) {
                        BasicText(block.raw, style = CittiTheme.typography.editor.copy(fontFamily = MONO, color = colors.inkMuted))
                    } else {
                        entries.forEach { PropertyRow(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PropertyRow(property: Property) {
    Row(horizontalArrangement = Arrangement.spacedBy(NotesSpacing.space3)) {
        BasicText(property.key, Modifier.widthIn(max = NotesSize.touchMin * 3), style = CittiTheme.typography.meta.colored(muted = true))
        BasicText(property.values.joinToString(", "), Modifier.weight(1f), style = CittiTheme.typography.meta.colored())
    }
}

private const val HEADING_2 = 0.8f
private const val HEADING_3 = 1.1f
private const val CELL_WIDTHS = 4
