package com.unsupportedpastels.hermesandroid.voice

/** Characters stripped from both ends before matching — mirrors the server's
 * `transcript.strip().lower().strip(".,!?;: \t\n\"'")` in tools/voice_mode.py. */
private const val STRIP_CHARS = ".,!?;: \t\n\"'"

/**
 * True when [transcript] is EXACTLY a configured stop phrase. Ported verbatim
 * from the server's `is_voice_stop_phrase`: deliberately strict — the whole
 * utterance, lowercased with surrounding punctuation stripped, must equal a
 * phrase, so "stop doing that and try again" still reaches the agent.
 * [stopPhrases] comes from server `voice.stop_phrases` (already normalized
 * lowercase by [VoiceServerConfig]); an empty list disables spoken stop.
 */
fun isVoiceStopPhrase(transcript: String, stopPhrases: List<String>): Boolean {
    if (transcript.isEmpty() || stopPhrases.isEmpty()) return false
    val cleaned = transcript.trim().lowercase().trim { it in STRIP_CHARS }
    if (cleaned.isEmpty()) return false
    return cleaned in stopPhrases
}
