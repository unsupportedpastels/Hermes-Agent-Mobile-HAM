package com.unsupportedpastels.hermesandroid.notifications

private const val MAX_NOTIFICATION_PREVIEW_CHARS = 240
private const val DEFAULT_NOTIFICATION_PREVIEW_LINES = 3

internal fun finalResponsePreview(
    text: String,
    maxLines: Int = DEFAULT_NOTIFICATION_PREVIEW_LINES,
): String {
    val cleaned = text
        .lineSequence()
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.matches(Regex("^#{1,6}\\s+.+"))) return@mapNotNull null
            trimmed
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
        }
        .filter(String::isNotBlank)
        .take(maxLines.coerceAtLeast(1))
        .joinToString("\n")
        .trim()
    return cleaned.ifEmpty { "Response completed" }
        .take(MAX_NOTIFICATION_PREVIEW_CHARS)
}

internal fun activeTurnTitle(count: Int): String =
    if (count <= 1) "Hermes is working" else "Hermes is working in $count sessions"
