package com.unsupportedpastels.hermesandroid.notifications

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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

internal data class SessionNotificationVisibility(
    val appForeground: Boolean = false,
    val windowFocused: Boolean = false,
    val visibleSessionId: DurableSessionId? = null,
)

internal fun shouldPostSessionNotification(
    sessionId: DurableSessionId,
    visibility: SessionNotificationVisibility,
): Boolean = !(
    visibility.appForeground &&
        visibility.windowFocused &&
        visibility.visibleSessionId == sessionId
    )

internal object SessionNotificationVisibilityRegistry {
    private val mutableStates = MutableStateFlow(SessionNotificationVisibility())
    val states = mutableStates.asStateFlow()

    fun publishAppForeground(foreground: Boolean) {
        mutableStates.value = mutableStates.value.copy(appForeground = foreground)
    }

    fun publishWindowFocused(focused: Boolean) {
        mutableStates.value = mutableStates.value.copy(windowFocused = focused)
    }

    fun publishVisibleSession(sessionId: DurableSessionId?) {
        mutableStates.value = mutableStates.value.copy(visibleSessionId = sessionId)
    }
}
