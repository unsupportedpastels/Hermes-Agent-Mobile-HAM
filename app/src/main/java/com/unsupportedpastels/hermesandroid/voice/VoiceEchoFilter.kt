package com.unsupportedpastels.hermesandroid.voice

/**
 * Self-capture guard for the playback-phase full-duplex listener, ported from
 * the server's `is_tts_echo` (tools/voice_mode.py, #75780): without acoustic
 * echo cancellation, speaker bleed can trip the barge trigger and get
 * transcribed nearly verbatim from the reply Hermes just spoke, creating a
 * TTS → STT → TTS feedback loop. A barge transcript that closely matches the
 * just-spoken text is discarded instead of submitted.
 *
 * Similarity is a character-level ratio (works across languages without
 * word tokenization). The whole-string check catches short replies; because a
 * barge capture is usually a short FRAGMENT of a longer reply, a window sized
 * to the transcript also slides across the spoken text. One divergence from
 * the Python original: the ratio here is LCS-based (difflib's matching-blocks
 * metric has no JVM twin), which reads slightly *higher* for shuffled text —
 * i.e. this port fails closed toward discarding echo.
 */
object VoiceEchoFilter {
    /** Similarity at or above this = self-capture (server default). */
    const val DEFAULT_SIMILARITY_THRESHOLD = 0.6

    /** Below this normalized length the window fallback is skipped: a genuine
     * short interjection ("yes") trivially matches some window of a long
     * reply that also contains it. */
    const val MIN_FRAGMENT_LENGTH_FOR_ECHO = 10

    /** Bound the O(n·m) comparisons; barge fragments are short anyway. */
    private const val MAX_COMPARE_CHARS = 1_500

    fun isTtsEcho(
        transcript: String,
        spokenText: String?,
        threshold: Double = DEFAULT_SIMILARITY_THRESHOLD,
    ): Boolean = bestSimilarity(transcript, spokenText) >= threshold

    /** Highest whole-or-windowed similarity in 0..1 (0.0 for empty inputs). */
    fun bestSimilarity(transcript: String, spokenText: String?): Double {
        if (transcript.isEmpty() || spokenText.isNullOrEmpty()) return 0.0
        val a = normalize(transcript).take(MAX_COMPARE_CHARS)
        val b = normalize(spokenText).take(MAX_COMPARE_CHARS)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var best = similarity(a, b)
        if (a.length < MIN_FRAGMENT_LENGTH_FOR_ECHO || a.length >= b.length) return best
        // Slide a transcript-sized window across the spoken text. The stride
        // (vs. the original's step of 1) keeps this cheap; a true echo still
        // overlaps some window almost completely.
        val stride = (a.length / 8).coerceAtLeast(1)
        var start = 0
        while (start <= b.length - a.length) {
            best = maxOf(best, similarity(a, b.substring(start, start + a.length)))
            start += stride
        }
        return best
    }

    private fun normalize(text: String): String =
        text.replace(Regex("\\s+"), " ").trim().lowercase()

    /** 2·LCS/(|a|+|b|) — the character-level analogue of difflib's ratio. */
    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var previous = IntArray(b.length + 1)
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                current[j] = if (a[i - 1] == b[j - 1]) {
                    previous[j - 1] + 1
                } else {
                    maxOf(previous[j], current[j - 1])
                }
            }
            val swap = previous
            previous = current
            current = swap
        }
        val lcs = previous[b.length]
        return 2.0 * lcs / (a.length + b.length)
    }
}
