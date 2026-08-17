package com.unsupportedpastels.hermesandroid.voice

/**
 * Prepares assistant text for text-to-speech. A faithful Kotlin port of the
 * desktop `sanitizeTextForSpeech` (apps/desktop/src/lib/speech-text.ts): fenced
 * code becomes "code block omitted", markdown tables are dropped, links read as
 * their label, URLs as " link ", emoji and a leading "thinking…" prefix are
 * removed, and residual markdown punctuation is stripped. The
 * [SpeechTextTest] cases mirror speech-text.test.ts one-for-one.
 */

private val EMOJI_RE = Regex(
    "(?:[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}]|[\\x{FE0F}\\x{200D}]|[\\x{E0020}-\\x{E007F}])+",
)
private val FENCED_CODE_RE = Regex("```[\\s\\S]*?(?:```|$)")
private const val CODE_BLOCK_SUMMARY = " code block omitted "
private val INLINE_CODE_RE = Regex("`([^`]+)`")
private val MARKDOWN_LINK_RE = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")
private val PARAGRAPH_BREAK_RE = Regex("[ \\t]*\\n{2,}[ \\t]*")
private val PUNCTUATED_PARAGRAPH_BREAK_RE =
    Regex("([.!?])([*_~`>\"'’”)}\\]]*)[ \\t]*\\n{2,}[ \\t]*")
private val SOFT_BREAK_RE = Regex("[ \\t]*\\n[ \\t]*")
private val CARRIAGE_RETURN_RE = Regex("\\r\\n?")
private val HYPHEN_LINE_BREAK_RE = Regex("(\\p{L})-\\n(\\p{L})")
private val HEADING_RE = Regex("^#{1,6}\\s+", RegexOption.MULTILINE)
private val RESIDUAL_MARKDOWN_RE = Regex("[*_~>#]")
private val LIST_MARKER_RE = Regex("^\\s*[-+*]\\s+", RegexOption.MULTILINE)
private val WHITESPACE_RE = Regex("\\s+")

private val THINKING_PREFIX_RE = Regex(
    "^\\s*(?:\\([^)\\n]{1,48}\\)\\s*)?(?:processing|thinking|reasoning|analyzing|pondering|" +
        "contemplating|musing|cogitating|ruminating|deliberating|mulling|reflecting|computing|" +
        "synthesizing|formulating|brainstorming)\\.\\.\\.\\s*",
    RegexOption.IGNORE_CASE,
)

private val URL_RE = Regex("\\bhttps?://\\S+", RegexOption.IGNORE_CASE)
private val MARKDOWN_TABLE_DELIMITER_CELL_RE = Regex("^:?-{3,}:?$")

private data class MarkdownTableRow(val blockquoteDepth: Int, val cells: List<String>)

private fun isUnescapedPipe(row: String, index: Int): Boolean {
    var backslashes = 0
    var cursor = index - 1
    while (cursor >= 0 && row[cursor] == '\\') {
        backslashes += 1
        cursor -= 1
    }
    return backslashes % 2 == 0
}

private fun unescapedPipeIndexes(row: String): List<Int> =
    row.indices.filter { row[it] == '|' && isUnescapedPipe(row, it) }

private fun splitMarkdownTableCells(row: String): List<String> {
    val cells = mutableListOf<String>()
    var cellStart = 0
    for (index in row.indices) {
        if (row[index] == '|' && isUnescapedPipe(row, index)) {
            cells.add(row.substring(cellStart, index).trim())
            cellStart = index + 1
        }
    }
    cells.add(row.substring(cellStart).trim())
    return cells
}

private fun parseMarkdownTableRow(line: String): MarkdownTableRow? {
    var row = line
    var blockquoteDepth = 0

    while (true) {
        val indentation = Regex("^[ \\t]*").find(row)?.value ?: ""
        if (indentation.contains('\t') || indentation.length > 3) {
            return null
        }
        row = row.substring(indentation.length)
        if (!row.startsWith('>')) {
            break
        }
        blockquoteDepth += 1
        row = row.substring(1)
        if (row.startsWith(' ')) {
            row = row.substring(1)
        }
    }

    row = row.trimEnd()

    val pipeIndexes = unescapedPipeIndexes(row)
    if (pipeIndexes.isEmpty()) {
        return null
    }

    val hasLeadingPipe = pipeIndexes.first() == 0
    val hasTrailingPipe = pipeIndexes.last() == row.length - 1

    if (hasLeadingPipe) {
        row = row.substring(1)
    }
    if (hasTrailingPipe) {
        row = row.substring(0, row.length - 1)
    }

    val cells = splitMarkdownTableCells(row)
    if (cells.size < 2 && !(hasLeadingPipe && hasTrailingPipe && cells.size == 1)) {
        return null
    }

    return MarkdownTableRow(blockquoteDepth, cells)
}

private fun stripMarkdownTables(text: String): String {
    val lines = CARRIAGE_RETURN_RE.replace(text, "\n").split("\n")
    val tableLines = mutableSetOf<Int>()

    var index = 1
    while (index < lines.size) {
        val delimiterRow = parseMarkdownTableRow(lines[index])
        val headerRow = parseMarkdownTableRow(lines[index - 1])

        if (
            delimiterRow == null ||
            headerRow == null ||
            !delimiterRow.cells.all { MARKDOWN_TABLE_DELIMITER_CELL_RE.matches(it) } ||
            headerRow.cells.size != delimiterRow.cells.size ||
            headerRow.blockquoteDepth != delimiterRow.blockquoteDepth
        ) {
            index += 1
            continue
        }

        tableLines.add(index - 1)
        tableLines.add(index)

        var rowIndex = index + 1
        while (rowIndex < lines.size) {
            val bodyRow = parseMarkdownTableRow(lines[rowIndex])
            if (bodyRow == null || bodyRow.blockquoteDepth != delimiterRow.blockquoteDepth) {
                break
            }
            tableLines.add(rowIndex)
            rowIndex += 1
        }

        index = rowIndex
    }

    return lines.filterIndexed { lineIndex, _ -> lineIndex !in tableLines }.joinToString("\n")
}

private fun normalizeLineBreaks(text: String): String =
    text
        .let { CARRIAGE_RETURN_RE.replace(it, "\n") }
        .let { HYPHEN_LINE_BREAK_RE.replace(it, "$1$2") }
        .let { PUNCTUATED_PARAGRAPH_BREAK_RE.replace(it, "$1$2 ") }
        .let { PARAGRAPH_BREAK_RE.replace(it, ". ") }
        .let { SOFT_BREAK_RE.replace(it, " ") }

fun sanitizeTextForSpeech(text: String): String =
    normalizeLineBreaks(stripMarkdownTables(text))
        .let { FENCED_CODE_RE.replace(it, CODE_BLOCK_SUMMARY) }
        .let { THINKING_PREFIX_RE.replace(it, " ") }
        .let { MARKDOWN_LINK_RE.replace(it, "$1") }
        .let { INLINE_CODE_RE.replace(it, "$1") }
        .let { URL_RE.replace(it, " link ") }
        .let { EMOJI_RE.replace(it, " ") }
        .let { HEADING_RE.replace(it, "") }
        .let { RESIDUAL_MARKDOWN_RE.replace(it, "") }
        .let { LIST_MARKER_RE.replace(it, "") }
        .let { WHITESPACE_RE.replace(it, " ") }
        .trim()
