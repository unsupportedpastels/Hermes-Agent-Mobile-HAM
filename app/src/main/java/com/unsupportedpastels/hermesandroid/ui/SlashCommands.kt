package com.unsupportedpastels.hermesandroid.ui

import com.unsupportedpastels.hermesandroid.gateway.SlashCompletionItem
import com.unsupportedpastels.hermesandroid.gateway.canonicalReasoningEffort

private val SlashCommandPattern = Regex("^/[^\\s/]*(?:\\s|$)")

fun isModelPickerCommand(text: String): Boolean = text.trim() == "/model"

fun isSteerCommand(text: String): Boolean {
    val command = text.trimStart()
    return command == "/steer" || command.startsWith("/steer ")
}

fun reasoningEffortCommand(text: String): String? {
    val tokens = text.trim().split(Regex("\\s+"))
    if (tokens.size != 2 || tokens[0] != "/reasoning") return null
    return canonicalReasoningEffort(tokens[1])
}

/**
 * True when [text] is a slash-command completion context, mirroring the desktop
 * `looksLikeSlashCommand`: a leading `/` command token with no second slash before
 * the first whitespace, then any in-progress argument text. Absolute paths
 * (`/home/user/file`) and prose containing `/` are not completion contexts.
 */
fun isSlashCommandContext(text: String): Boolean = SlashCommandPattern.containsMatchIn(text)

/**
 * Applies a completion row to the current composer text using desktop `replace_from`
 * semantics: keep the prefix before [replaceFrom], append the item text, and drop the
 * remainder after the replacement point. A leading slash on the item is dropped only
 * when the composer already has a `/` immediately before the replace point, mirroring
 * the desktop editor's `applyCompletion`.
 */
fun applySlashCompletion(
    current: String,
    item: SlashCompletionItem,
    replaceFrom: Int,
): String {
    val clamped = replaceFrom.coerceIn(0, current.length)
    var addition = item.text
    if (current.getOrNull(clamped - 1) == '/' && addition.startsWith("/")) {
        addition = addition.drop(1)
    }
    return current.substring(0, clamped) + addition
}
