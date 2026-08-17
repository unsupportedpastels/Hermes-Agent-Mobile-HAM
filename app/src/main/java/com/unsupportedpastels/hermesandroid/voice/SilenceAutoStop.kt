package com.unsupportedpastels.hermesandroid.voice

/** Verdict for one amplitude sample during an utterance recording. */
enum class SilenceAutoStopDecision {
    Continue,

    /** Speech was captured and has ended (or the cap was hit) — transcribe. */
    Stop,

    /** Nothing but ambient noise was ever heard — discard without uploading. */
    StopEmpty,
}

/**
 * Speech-relative end-pointing over Android `MediaRecorder.maxAmplitude` peaks.
 *
 * The server's `voice.silence_threshold` is calibrated for the desktop capture
 * scale; on phone mics real room tone hovers *around* it (and the first few
 * hundred milliseconds under-read while capture ramps up), so a fixed floor
 * comparison either never detects silence or resets on every ambient bounce.
 * Instead:
 *
 * - Speech is detected when a peak clearly exceeds the room (absolute minimum
 *   plus calibrated-ambient scaling).
 * - Once speech is seen, the quiet ceiling is *relative to the utterance's own
 *   loudness* (peak/8, never below the configured floor), so room tone sits
 *   far under it regardless of mic scale.
 * - Quiet time accumulates per sample and a loud sample only *drains* it
 *   ([LOUD_DRAIN_FACTOR]× the sample interval) rather than zeroing it, so a
 *   page rustle or breath cannot restart the silence clock, while resumed
 *   speech quickly empties it.
 * - A recording with no speech at all resolves to
 *   [SilenceAutoStopDecision.StopEmpty] after [noSpeechTimeoutMillis] so pure
 *   room tone is never uploaded.
 */
class SilenceAutoStop(
    configuredThreshold: Int,
    private val silenceMillis: Long,
    private val maxMillis: Long,
    private val calibrationMillis: Long = CALIBRATION_MILLIS,
    private val noSpeechTimeoutMillis: Long = NO_SPEECH_TIMEOUT_MILLIS,
) {
    private val configured = configuredThreshold.coerceIn(1, MAX_SILENCE_FLOOR)
    private var ambientPeak = 0
    private var utterancePeak = 0
    private var speechSeen = false
    private var quietMillis = 0L
    private var lastElapsedMillis = 0L

    private val silenceFloor: Int
        get() = maxOf(configured, (ambientPeak * 7) / 4).coerceAtMost(MAX_SILENCE_FLOOR)

    private val speechLevel: Int
        get() = maxOf(silenceFloor * 3, MIN_SPEECH_LEVEL)

    /** Quiet ceiling once speech was heard: relative to how loud the user is. */
    private val quietCeiling: Int
        get() = maxOf(silenceFloor, utterancePeak / QUIET_CEILING_DIVISOR)

    /** Bounded diagnostic snapshot (scale values only; no audio content). */
    fun debugState(): String =
        "floor=$silenceFloor ceiling=$quietCeiling ambient=$ambientPeak " +
            "utterancePeak=$utterancePeak speechSeen=$speechSeen quietMs=$quietMillis " +
            "lastElapsed=$lastElapsedMillis silenceMs=$silenceMillis maxMs=$maxMillis"

    fun feed(elapsedMillis: Long, amplitude: Int): SilenceAutoStopDecision {
        val intervalMillis = (elapsedMillis - lastElapsedMillis).coerceIn(0, 1_000)
        lastElapsedMillis = elapsedMillis
        if (elapsedMillis >= maxMillis) return SilenceAutoStopDecision.Stop

        if (elapsedMillis < calibrationMillis) {
            if (amplitude > ambientPeak) ambientPeak = amplitude
            return SilenceAutoStopDecision.Continue
        }

        if (amplitude >= speechLevel) {
            speechSeen = true
            if (amplitude > utterancePeak) utterancePeak = amplitude
        }
        if (!speechSeen) {
            return if (elapsedMillis >= noSpeechTimeoutMillis) {
                SilenceAutoStopDecision.StopEmpty
            } else {
                SilenceAutoStopDecision.Continue
            }
        }

        if (amplitude < quietCeiling) {
            quietMillis += intervalMillis
            if (silenceMillis > 0 && quietMillis >= silenceMillis) {
                return SilenceAutoStopDecision.Stop
            }
        } else {
            quietMillis = (quietMillis - intervalMillis * LOUD_DRAIN_FACTOR).coerceAtLeast(0L)
        }
        return SilenceAutoStopDecision.Continue
    }

    companion object {
        const val CALIBRATION_MILLIS = 600L
        const val NO_SPEECH_TIMEOUT_MILLIS = 8_000L

        /** Even a loud room must leave speech peaks detectable above the floor. */
        private const val MAX_SILENCE_FLOOR = 6_000

        private const val MIN_SPEECH_LEVEL = 2_000

        /** Quiet ceiling = utterance peak divided by this. */
        private const val QUIET_CEILING_DIVISOR = 8

        /** One loud sample drains this many samples' worth of quiet time. */
        private const val LOUD_DRAIN_FACTOR = 3
    }
}
