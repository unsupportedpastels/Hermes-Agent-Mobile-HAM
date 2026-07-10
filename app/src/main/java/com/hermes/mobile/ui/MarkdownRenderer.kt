package com.hermes.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Block model ──────────────────────────────────────────────────────────────

private sealed interface MdBlock {
    data class Thinking(val text: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class ListItem(val number: Int?, val text: String, val subItems: List<ListItem>) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Spacer(val height: Int = 0) : MdBlock
}

private data class MdListNode(val number: Int?, val text: String, val indent: Int)

// ── Thinking tag helpers (built from char codes to avoid XML parsing issues) ──

private val THINK_OPEN: String = String(charArrayOf('<', 't', 'h', 'i', 'n', 'k', '>'))
private val THINK_CLOSE: String = String(charArrayOf('<', '/', 't', 'h', 'i', 'n', 'k', '>'))

// ── Parser ──────────────────────────────────────────────────────────────────

private fun parseMarkdown(raw: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()

    // Extract thinking blocks first
    val (thinkingText, body) = extractThinking(raw)
    if (thinkingText.isNotBlank()) blocks.add(MdBlock.Thinking(thinkingText.trim()))

    val bodyLines = body.lines()
    var i = 0

    while (i < bodyLines.size) {
        val line = bodyLines[i]
        val trimmed = line.trim()

        // Fenced code block
        if (trimmed.startsWith("```")) {
            val lang = trimmed.removePrefix("```").trim()
            val sb = StringBuilder()
            i++
            while (i < bodyLines.size && !bodyLines[i].trim().startsWith("```")) {
                sb.appendLine(bodyLines[i])
                i++
            }
            if (i < bodyLines.size) i++ // skip closing fence
            blocks.add(MdBlock.Code(lang, sb.toString().trimEnd('\n')))
            continue
        }

        // Table — header row with | followed by separator row |---|
        if (trimmed.contains('|') && i + 1 < bodyLines.size && isTableSeparator(bodyLines[i + 1])) {
            val headers = splitTableRow(trimmed)
            i += 2 // skip header + separator
            val rows = mutableListOf<List<String>>()
            while (i < bodyLines.size && bodyLines[i].trim().contains('|') && bodyLines[i].trim().isNotEmpty()) {
                rows.add(splitTableRow(bodyLines[i].trim()))
                i++
            }
            blocks.add(MdBlock.Table(headers, rows))
            continue
        }

        // Headings
        val headingMatch = Regex("^(#{1,4})\\s+(.+)$").matchEntire(trimmed)
        if (headingMatch != null) {
            blocks.add(MdBlock.Heading(headingMatch.groupValues[1].length, headingMatch.groupValues[2].trim()))
            i++
            continue
        }

        // Blockquote
        if (trimmed.startsWith(">")) {
            val sb = StringBuilder()
            while (i < bodyLines.size && bodyLines[i].trim().startsWith(">")) {
                sb.appendLine(bodyLines[i].trim().removePrefix(">").trim())
                i++
            }
            blocks.add(MdBlock.Quote(sb.toString().trim()))
            continue
        }

        // Lists (ordered/unordered)
        val listRegex = Regex("^(\\s*)[-*+]|(\\s*\\d+\\.)\\s+(.+)$")
        if (listRegex.matches(trimmed) || (trimmed.isNotEmpty() && trimmed[0] in "-*+" && trimmed.length > 1 && trimmed[1] == ' ')) {
            while (i < bodyLines.size) {
                val l = bodyLines[i].trim()
                val m = Regex("^(\\s*)([-*+]|(\\d+)\\.)\\s+(.+)$").matchEntire(l)
                    ?: Regex("^(\\s*)([-*+])\\s+(.+)$").matchEntire(l)
                if (m != null) {
                    val num = m.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.toIntOrNull()
                    val text = m.groupValues[4].ifBlank { m.groupValues.getOrNull(5) ?: "" }
                    blocks.add(MdBlock.ListItem(num, text, emptyList()))
                    i++
                } else break
            }
            continue
        }

        // Blank line
        if (trimmed.isEmpty()) {
            i++
            continue
        }

        // Paragraph — consume consecutive non-empty, non-special lines
        val sb = StringBuilder()
        while (i < bodyLines.size && bodyLines[i].trim().isNotEmpty() &&
            !bodyLines[i].trim().startsWith("```") &&
            !bodyLines[i].trim().startsWith("#") &&
            !bodyLines[i].trim().startsWith(">") &&
            !isTableSeparator(bodyLines[i]) &&
            !listRegex.matches(bodyLines[i].trim())
        ) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(bodyLines[i])
            i++
        }
        if (sb.isNotEmpty()) blocks.add(MdBlock.Paragraph(sb.toString()))
    }

    return blocks
}

private fun extractThinking(raw: String): Pair<String, String> {
    // Thinking tags (built from char codes to avoid XML parsing issues)
    val tagMatch = Regex(Regex.escape(THINK_OPEN) + "(.*?)" + Regex.escape(THINK_CLOSE), RegexOption.DOT_MATCHES_ALL).find(raw)
    if (tagMatch != null) {
        val thinking = tagMatch.groupValues[1]
        val body = raw.replace(tagMatch.value, "").trim()
        return thinking to body
    }

    // Fenced thinking block: ```thinking\n...\n```
    val thinkFence = Regex("(?s)```thinking\\s*\\n(.*?)```", RegexOption.IGNORE_CASE)
    val fenceMatch = thinkFence.find(raw)
    if (fenceMatch != null) {
        val thinking = fenceMatch.groupValues[1]
        val body = raw.replace(fenceMatch.value, "").trim()
        return thinking to body
    }

    return "" to raw
}

private fun isTableSeparator(line: String): Boolean {
    val trimmed = line.trim()
    if (!trimmed.contains('|')) return false
    val cleaned = trimmed.replace("|", "").replace("-", "").replace(":", "").replace(" ", "")
    return cleaned.isEmpty() && trimmed.contains('-')
}

private fun splitTableRow(line: String): List<String> {
    val cleaned = line.removePrefix("|").removeSuffix("|")
    return cleaned.split("|").map { it.trim() }
}

// ── Inline parser (bold, italic, code, strikethrough) ────────────────────────

internal fun renderInline(
    text: String,
    baseColor: Color,
    codeBackground: Color,
    linkColor: Color,
): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        // Inline code `code`
        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, background = codeBackground)) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        // Bold **text** or __text__
        if ((text.startsWith("**", i) || text.startsWith("__", i)) && i + 1 < text.length) {
            val marker = text.substring(i, i + 2)
            val end = text.indexOf(marker, i + 2)
            if (end > i + 1) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(text.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        }
        // Italic *text* or _text_. CommonMark does not treat underscores
        // inside identifiers (for example STREAM_COMPLETE_OK) as emphasis.
        if ((text[i] == '*' || text[i] == '_') && i + 1 < text.length && text[i + 1] != text[i]) {
            val end = findItalicEnd(text, i, text[i])
            if (end > i + 1) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        // Strikethrough ~~text~~
        if (text.startsWith("~~", i)) {
            val end = text.indexOf("~~", i + 2)
            if (end > i + 1) {
                withStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)) {
                    append(text.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        }
        // Link [text](url)
        if (text[i] == '[') {
            val closeBracket = text.indexOf(']', i + 1)
            if (closeBracket > i && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                val closeParen = text.indexOf(')', closeBracket + 2)
                if (closeParen > closeBracket) {
                    withStyle(SpanStyle(color = linkColor)) {
                        append(text.substring(i + 1, closeBracket))
                    }
                    i = closeParen + 1
                    continue
                }
            }
        }
        append(text[i])
        i++
    }
}

private fun findItalicEnd(text: String, start: Int, marker: Char): Int {
    if (marker == '_' && start > 0 && text[start - 1].isLetterOrDigit()) return -1
    var end = text.indexOf(marker, start + 1)
    while (end >= 0) {
        val closesAtWordBoundary = marker != '_' || end == text.lastIndex || !text[end + 1].isLetterOrDigit()
        if (closesAtWordBoundary) return end
        end = text.indexOf(marker, end + 1)
    }
    return -1
}

// ── Composable renderer ────────────────────────────────────────────────────

@Composable
fun MarkdownText(
    raw: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onBackground,
) {
    val context = LocalContext.current
    val codeBackground = MaterialTheme.colorScheme.surface
    val linkColor = MaterialTheme.colorScheme.primary
    val blocks = remember(raw) { parseMarkdown(raw) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Thinking -> ThinkingBlock(block.text)
                is MdBlock.Code -> CodeBlock(block.language, block.code, context)
                is MdBlock.Table -> TableBlock(block.headers, block.rows)
                is MdBlock.Heading -> Text(
                    renderInline(block.text, textColor, codeBackground, linkColor),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineMedium
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                    color = textColor,
                )
                is MdBlock.Quote -> Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        renderInline(block.text, textColor.copy(alpha = 0.8f), codeBackground, linkColor),
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor.copy(alpha = 0.8f),
                    )
                }
                is MdBlock.ListItem -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val bullet = block.number?.let { "$it." } ?: "\u2022"
                    Text(bullet, color = textColor, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        renderInline(block.text, textColor, codeBackground, linkColor),
                        color = textColor,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
                is MdBlock.Paragraph -> Text(
                    renderInline(block.text, textColor, codeBackground, linkColor),
                    color = textColor,
                    style = MaterialTheme.typography.bodyLarge,
                )
                is MdBlock.Spacer -> Spacer(Modifier.height(block.height.dp))
            }
        }
    }
}

// ── Thinking block ──────────────────────────────────────────────────────────

@Composable
private fun ThinkingBlock(text: String) {
    var expanded by remember { mutableStateOf(true) }

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Psychology,
                    "Thinking",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Thinking",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Outlined.KeyboardArrowDown
                    else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Text(
                    text,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Code block ──────────────────────────────────────────────────────────────

@Composable
private fun CodeBlock(language: String, code: String, context: Context) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    language.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Outlined.ContentCopy,
                    "Copy",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("code", code))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SelectionContainer {
                Text(
                    code,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .horizontalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

// ── Table block ─────────────────────────────────────────────────────────────

@Composable
private fun TableBlock(headers: List<String>, rows: List<List<String>>) {
    val codeBackground = MaterialTheme.colorScheme.surface
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val linkColor = MaterialTheme.colorScheme.primary

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawLine(borderColor, Offset(0f, size.height), Offset(size.width, size.height), 1f)
                    },
            ) {
                headers.forEachIndexed { index, header ->
                    Text(
                        renderInline(header, MaterialTheme.colorScheme.onSurface, codeBackground, linkColor),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (index < headers.lastIndex) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(20.dp)
                                .background(borderColor),
                        )
                    }
                }
            }
            // Data rows
            rows.forEachIndexed { rowIdx, row ->
                val rowBg = if (rowIdx % 2 == 0) Color.Transparent
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(rowBg)
                        .drawBehind {
                            if (rowIdx < rows.lastIndex) {
                                drawLine(borderColor, Offset(0f, size.height), Offset(size.width, size.height), 1f)
                            }
                        },
                ) {
                    row.forEachIndexed { colIdx, cell ->
                        Text(
                            renderInline(cell, MaterialTheme.colorScheme.onSurface, codeBackground, linkColor),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (colIdx < row.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(20.dp)
                                    .background(borderColor),
                            )
                        }
                    }
                }
            }
        }
    }
}
