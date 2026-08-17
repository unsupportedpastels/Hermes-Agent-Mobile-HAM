package com.unsupportedpastels.hermesandroid.voice

import java.util.Base64

/** Decoded TTS audio ready for playback. */
class SpeechAudio(val bytes: ByteArray, val mimeType: String) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpeechAudio) return false
        return mimeType == other.mimeType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * mimeType.hashCode() + bytes.contentHashCode()
}

/**
 * Decode a `data:<mime>;base64,<...>` audio URL (as returned by
 * `POST /api/audio/speak`) into raw bytes. Pure (JVM Base64) so it is
 * unit-testable. Returns null if the URL is not a base64 audio data URL.
 */
fun decodeAudioDataUrl(dataUrl: String): SpeechAudio? {
    if (!dataUrl.startsWith("data:")) return null
    val comma = dataUrl.indexOf(',')
    if (comma < 0) return null
    val header = dataUrl.substring("data:".length, comma)
    if (!header.contains(";base64")) return null
    val mimeType = header.substringBefore(';').ifBlank { "audio/mpeg" }
    return try {
        SpeechAudio(Base64.getDecoder().decode(dataUrl.substring(comma + 1)), mimeType)
    } catch (_: IllegalArgumentException) {
        null
    }
}
