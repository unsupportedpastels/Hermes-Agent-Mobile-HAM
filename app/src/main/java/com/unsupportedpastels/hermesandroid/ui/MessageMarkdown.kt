package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

internal sealed interface MarkdownBlock

internal enum class MarkdownTextKind {
    Paragraph,
    Heading,
    Bullet,
    Numbered,
    Quote,
}

internal data class MarkdownInline(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strikethrough: Boolean = false,
    val link: String? = null,
)

internal data class MarkdownTextBlock(
    val inlines: List<MarkdownInline>,
    val kind: MarkdownTextKind = MarkdownTextKind.Paragraph,
    val prefix: String? = null,
    val headingLevel: Int = 0,
    val indentLevel: Int = 0,
) : MarkdownBlock {
    val plainText: String = inlines.joinToString(separator = "") { it.text }
}

internal data class MarkdownCodeBlock(
    val code: String,
    val language: String? = null,
) : MarkdownBlock

private val unorderedListPattern = Regex("^(\\s*)[-+*]\\s+(.+)$")
private val orderedListPattern = Regex("^(\\s*)(\\d+[.)])\\s+(.+)$")
private val headingPattern = Regex("^(#{1,6})\\s+(.+)$")

internal fun parseMessageMarkdown(source: String): List<MarkdownBlock> {
    if (source.isEmpty()) return emptyList()
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraph.isEmpty()) return
        val text = paragraph.joinToString("\n").trimEnd()
        if (text.isNotEmpty()) {
            blocks += MarkdownTextBlock(parseMarkdownInlines(text))
        }
        paragraph.clear()
    }

    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmedStart = line.trimStart()
        if (trimmedStart.startsWith("```")) {
            flushParagraph()
            val language = trimmedStart.removePrefix("```").trim()
                .take(32)
                .ifBlank { null }
            val codeLines = mutableListOf<String>()
            index += 1
            while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                codeLines += lines[index]
                index += 1
            }
            blocks += MarkdownCodeBlock(
                code = codeLines.joinToString("\n").trimEnd('\n'),
                language = language,
            )
            if (index < lines.size) index += 1
            continue
        }
        if (line.isBlank()) {
            flushParagraph()
            index += 1
            continue
        }

        val heading = headingPattern.matchEntire(trimmedStart)
        if (heading != null) {
            flushParagraph()
            blocks += MarkdownTextBlock(
                inlines = parseMarkdownInlines(heading.groupValues[2].trimEnd()),
                kind = MarkdownTextKind.Heading,
                headingLevel = heading.groupValues[1].length,
            )
            index += 1
            continue
        }

        val unordered = unorderedListPattern.matchEntire(line)
        if (unordered != null) {
            flushParagraph()
            blocks += MarkdownTextBlock(
                inlines = parseMarkdownInlines(unordered.groupValues[2].trimEnd()),
                kind = MarkdownTextKind.Bullet,
                prefix = "•",
                indentLevel = (unordered.groupValues[1].length / 2).coerceAtMost(4),
            )
            index += 1
            continue
        }

        val ordered = orderedListPattern.matchEntire(line)
        if (ordered != null) {
            flushParagraph()
            blocks += MarkdownTextBlock(
                inlines = parseMarkdownInlines(ordered.groupValues[3].trimEnd()),
                kind = MarkdownTextKind.Numbered,
                prefix = ordered.groupValues[2],
                indentLevel = (ordered.groupValues[1].length / 2).coerceAtMost(4),
            )
            index += 1
            continue
        }

        if (trimmedStart.startsWith("> ")) {
            flushParagraph()
            blocks += MarkdownTextBlock(
                inlines = parseMarkdownInlines(trimmedStart.removePrefix("> ").trimEnd()),
                kind = MarkdownTextKind.Quote,
            )
            index += 1
            continue
        }

        paragraph += line.trimEnd()
        index += 1
    }
    flushParagraph()
    return blocks
}

private data class InlineState(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strikethrough: Boolean = false,
    val link: String? = null,
)

private fun parseMarkdownInlines(text: String, inherited: InlineState = InlineState()): List<MarkdownInline> {
    val output = mutableListOf<MarkdownInline>()

    fun append(inline: MarkdownInline) {
        if (inline.text.isEmpty()) return
        val last = output.lastOrNull()
        if (last != null && last.copy(text = "") == inline.copy(text = "")) {
            output[output.lastIndex] = last.copy(text = last.text + inline.text)
        } else {
            output += inline
        }
    }

    fun appendPlain(value: String) {
        append(
            MarkdownInline(
                text = value,
                bold = inherited.bold,
                italic = inherited.italic,
                strikethrough = inherited.strikethrough,
                link = inherited.link,
            ),
        )
    }

    var index = 0
    while (index < text.length) {
        if (text[index] == '`') {
            val close = text.indexOf('`', index + 1)
            if (close > index + 1) {
                append(
                    MarkdownInline(
                        text = text.substring(index + 1, close),
                        bold = inherited.bold,
                        italic = inherited.italic,
                        code = true,
                        strikethrough = inherited.strikethrough,
                        link = inherited.link,
                    ),
                )
                index = close + 1
                continue
            }
        }

        if (text[index] == '[') {
            val labelEnd = text.indexOf("](", index + 1)
            val urlEnd = if (labelEnd >= 0) text.indexOf(')', labelEnd + 2) else -1
            if (labelEnd > index + 1 && urlEnd > labelEnd + 2) {
                val label = text.substring(index + 1, labelEnd)
                val url = text.substring(labelEnd + 2, urlEnd).trim()
                if (url.isNotEmpty()) {
                    parseMarkdownInlines(label, inherited.copy(link = url)).forEach(::append)
                    index = urlEnd + 1
                    continue
                }
            }
        }

        val marker = when {
            text.startsWith("***", index) -> "***"
            text.startsWith("___", index) -> "___"
            text.startsWith("**", index) -> "**"
            text.startsWith("__", index) -> "__"
            text.startsWith("~~", index) -> "~~"
            text.startsWith("*", index) -> "*"
            text.startsWith("_", index) -> "_"
            else -> null
        }
        if (marker != null) {
            val close = text.indexOf(marker, index + marker.length)
            if (close > index + marker.length) {
                val nestedState = when (marker) {
                    "***", "___" -> inherited.copy(bold = true, italic = true)
                    "**", "__" -> inherited.copy(bold = true)
                    "~~" -> inherited.copy(strikethrough = true)
                    else -> inherited.copy(italic = true)
                }
                parseMarkdownInlines(
                    text.substring(index + marker.length, close),
                    nestedState,
                ).forEach(::append)
                index = close + marker.length
                continue
            }
        }

        appendPlain(text[index].toString())
        index += 1
    }
    return output
}

@Composable
internal fun MarkdownMessage(
    text: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { parseMessageMarkdown(text) }
    SelectionContainer(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownTextBlock -> MarkdownText(block)
                    is MarkdownCodeBlock -> MarkdownCode(block)
                }
            }
        }
    }
}

@Composable
private fun MarkdownText(block: MarkdownTextBlock) {
    val annotated = annotatedMarkdown(block.inlines)
    val textStyle = when (block.kind) {
        MarkdownTextKind.Heading -> when (block.headingLevel) {
            1 -> MaterialTheme.typography.headlineSmall
            2 -> MaterialTheme.typography.titleLarge
            else -> MaterialTheme.typography.titleMedium
        }
        else -> MaterialTheme.typography.bodyLarge
    }
    val rowModifier = Modifier
        .fillMaxWidth()
        .padding(start = (block.indentLevel * 12).dp)
        .then(
            if (block.kind == MarkdownTextKind.Quote) {
                Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            } else {
                Modifier
            },
        )

    if (block.prefix != null) {
        Row(
            modifier = rowModifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = block.prefix,
                style = textStyle,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = annotated,
                modifier = Modifier.weight(1f),
                style = textStyle,
            )
        }
    } else {
        Text(
            text = annotated,
            modifier = rowModifier,
            style = textStyle,
        )
    }
}

@Composable
private fun annotatedMarkdown(inlines: List<MarkdownInline>): AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        inlines.forEach { inline ->
            withStyle(
                SpanStyle(
                    fontWeight = if (inline.bold) FontWeight.SemiBold else null,
                    fontStyle = if (inline.italic) FontStyle.Italic else null,
                    fontFamily = if (inline.code) FontFamily.Monospace else null,
                    background = if (inline.code) codeBackground else androidx.compose.ui.graphics.Color.Unspecified,
                    color = if (inline.link != null) linkColor else androidx.compose.ui.graphics.Color.Unspecified,
                    textDecoration = when {
                        inline.strikethrough && inline.link != null -> TextDecoration.combine(
                            listOf(TextDecoration.LineThrough, TextDecoration.Underline),
                        )
                        inline.strikethrough -> TextDecoration.LineThrough
                        inline.link != null -> TextDecoration.Underline
                        else -> null
                    },
                ),
            ) {
                append(inline.text)
            }
        }
    }
}

@Composable
private fun MarkdownCode(block: MarkdownCodeBlock) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp),
            )
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        block.language?.let { language ->
            Text(
                text = language.uppercase(),
                modifier = Modifier.padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = block.code,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
