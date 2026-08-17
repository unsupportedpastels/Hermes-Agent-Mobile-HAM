package com.unsupportedpastels.hermesandroid.voice

internal object VoiceInputPolicy {
    const val MAX_RESULT_CHARS = 4_096

    fun scopeKey(
        serverOrigin: String?,
        profile: String,
        durableSessionId: String,
    ): String = listOf(serverOrigin.orEmpty(), profile, durableSessionId)
        .joinToString("|") { value -> "${value.length}:$value" }

    fun bestResult(results: List<String>?): String? = results
        ?.asSequence()
        ?.map { it.trim() }
        ?.firstOrNull(String::isNotEmpty)
        ?.take(MAX_RESULT_CHARS)

    fun mergeDraft(current: String, recognized: String): String {
        val bounded = recognized.trim().take(MAX_RESULT_CHARS)
        return when {
            bounded.isEmpty() -> current
            current.isEmpty() || current.last().isWhitespace() -> current + bounded
            else -> "$current $bounded"
        }
    }
}