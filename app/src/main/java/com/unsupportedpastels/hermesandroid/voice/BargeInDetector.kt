package com.unsupportedpastels.hermesandroid.voice

/** Server-configured trip point: the silence floor scaled by
 * `voice.barge_in_threshold_multiplier`. On Android's peak-amplitude scale this
 * is a lower bound only — [BargeInDetector] raises it above the measured
 * ambient floor, mirroring Desktop's quiet-floor calibration. */
fun VoiceServerConfig.bargeTriggerAmplitude(): Int =
    (silenceThreshold * bargeInThresholdMultiplier).toInt()
        .coerceIn(minOf(silenceThreshold + 1, 32_767), 32_767)

/**
 * Deterministic amplitude-based barge-in detector. The grace window after the
 * monitor starts (server `voice.barge_in_grace_seconds`, covering TTS onset
 * bleed) doubles as ambient calibration: the effective trip point is the
 * loudest of the server-configured trigger, 3× the calibrated ambient peak,
 * and an absolute speech minimum — bounded so real speech always stays
 * detectable. A trigger then requires [requiredConsecutiveSamples] consecutive
 * samples at or above that trip point, so a cough or bump doesn't cut the
 * reply. Pure and clock-free: the caller feeds (elapsedMillis, amplitude).
 */
class BargeInDetector(
    private val configuredTriggerAmplitude: Int,
    private val graceMillis: Long,
    private val requiredConsecutiveSamples: Int = DEFAULT_REQUIRED_CONSECUTIVE_SAMPLES,
) {
    private var consecutive = 0
    private var triggered = false

    /** Loudest sample seen during the grace/calibration window. */
    var ambientPeakAmplitude: Int = 0
        private set

    val effectiveTriggerAmplitude: Int
        get() = maxOf(
            configuredTriggerAmplitude,
            ambientPeakAmplitude * 3,
            MIN_TRIGGER_AMPLITUDE,
        ).coerceAtMost(MAX_TRIGGER_AMPLITUDE)

    private var playbackStartedAtMillis = -1L
    private var playbackBleedPeak = 0

    /** While TTS is audible the phone's own speaker bleeds into the mic far
     * above room tone. Mirroring Desktop's voice-barge-in.ts: the first
     * [PLAYBACK_GRACE_MILLIS] of each playback are a no-trigger window that
     * doubles as bleed calibration, and the trip point is the loudest of the
     * playback minimum (0.14 of full scale), 1.5× the measured bleed peak, and
     * the ambient trigger — capped at the ceiling (0.37) so genuinely loud
     * user speech still barges through. */
    val playbackTriggerAmplitude: Int
        get() = maxOf(
            effectiveTriggerAmplitude,
            PLAYBACK_MIN_TRIGGER,
            (playbackBleedPeak * 3) / 2,
        ).coerceAtMost(PLAYBACK_CEILING)

    /** Post-trigger silence floor for utterance end-pointing. */
    val silenceFloorAmplitude: Int
        get() = maxOf((ambientPeakAmplitude * 7) / 4, MIN_SILENCE_FLOOR)
            .coerceAtMost(MAX_SILENCE_FLOOR)

    /**
     * Feed one sample; returns true exactly once, at the trigger moment.
     * [playbackActive] = TTS is audible right now: the trip point rises to the
     * playback clamp and the sustained-speech requirement lengthens, so the
     * reply's own audio cannot cut the reply.
     */
    fun onSample(elapsedMillis: Long, amplitude: Int, playbackActive: Boolean = false): Boolean {
        if (triggered) return false
        if (elapsedMillis < graceMillis) {
            if (amplitude > ambientPeakAmplitude) ambientPeakAmplitude = amplitude
            consecutive = 0
            return false
        }
        if (playbackActive) {
            if (playbackStartedAtMillis < 0) playbackStartedAtMillis = elapsedMillis
            // Playback grace: no triggering while the speaker's own onset
            // bleeds into the mic; the window calibrates the bleed level.
            if (elapsedMillis - playbackStartedAtMillis < PLAYBACK_GRACE_MILLIS) {
                if (amplitude > playbackBleedPeak) playbackBleedPeak = amplitude
                consecutive = 0
                return false
            }
        } else {
            playbackStartedAtMillis = -1L
        }
        val trigger = if (playbackActive) playbackTriggerAmplitude else effectiveTriggerAmplitude
        val required = if (playbackActive) {
            PLAYBACK_REQUIRED_CONSECUTIVE_SAMPLES
        } else {
            requiredConsecutiveSamples
        }
        if (amplitude >= trigger) {
            consecutive++
            if (consecutive >= required) {
                triggered = true
                return true
            }
        } else {
            consecutive = 0
        }
        return false
    }

    companion object {
        /** At the 100 ms sampling cadence this is ~300 ms of sustained speech. */
        const val DEFAULT_REQUIRED_CONSECUTIVE_SAMPLES = 3

        /** Below this peak it isn't speech on any real device. */
        private const val MIN_TRIGGER_AMPLITUDE = 2_500

        /** Even loud playback bleed must leave genuine speech detectable. */
        private const val MAX_TRIGGER_AMPLITUDE = 30_000

        /** Desktop's playback-phase minimum (0.14 × 32767). */
        private const val PLAYBACK_MIN_TRIGGER = 4_600

        /** Desktop's playback-phase ceiling (0.37 × 32767). */
        private const val PLAYBACK_CEILING = 12_200

        /** ~500 ms of sustained speech before cutting audible playback. */
        private const val PLAYBACK_REQUIRED_CONSECUTIVE_SAMPLES = 5

        /** Desktop's playback grace: no triggers while bleed calibrates. */
        private const val PLAYBACK_GRACE_MILLIS = 500L

        private const val MIN_SILENCE_FLOOR = 400
        private const val MAX_SILENCE_FLOOR = 6_000
    }
}
