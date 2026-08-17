package com.unsupportedpastels.hermesandroid.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BargeInDetectorTest {
    private fun detector(
        trigger: Int = 600,
        graceMillis: Long = 500L,
        consecutive: Int = 3,
    ) = BargeInDetector(trigger, graceMillis, consecutive)

    @Test
    fun sustainedSpeechAfterGraceTriggersOnce() {
        val d = detector()
        // Quiet calibration, then sustained speech-level amplitude.
        assertFalse(d.onSample(0, 300))
        assertFalse(d.onSample(200, 300))
        assertFalse(d.onSample(600, 8_000))
        assertFalse(d.onSample(700, 8_000))
        assertTrue(d.onSample(800, 8_000))
        // Never re-triggers.
        assertFalse(d.onSample(900, 8_000))
    }

    @Test
    fun ambientCalibrationRaisesTheTripPoint() {
        val d = detector()
        // Noisy room: ambient peaks at 1500 during calibration.
        d.onSample(0, 1_500)
        d.onSample(200, 1_500)
        assertEquals(4_500, d.effectiveTriggerAmplitude)
        // Ambient-level noise after grace never triggers.
        for (i in 0..50) {
            assertFalse(d.onSample(600L + i * 100, 1_500))
        }
        // Real speech above the raised trip point still does.
        assertFalse(d.onSample(6_000, 9_000))
        assertFalse(d.onSample(6_100, 9_000))
        assertTrue(d.onSample(6_200, 9_000))
    }

    @Test
    fun quietRoomStillRequiresSpeechLevelAmplitude() {
        val d = detector()
        d.onSample(0, 50)
        // Configured trigger is 600, but 700 is not speech on the peak scale;
        // the absolute minimum keeps breaths from cutting the reply.
        for (i in 0..20) {
            assertFalse(d.onSample(600L + i * 100, 700))
        }
        assertTrue(d.effectiveTriggerAmplitude >= 2_500)
    }

    @Test
    fun shortSpikeDoesNotTrigger() {
        val d = detector()
        assertFalse(d.onSample(600, 20_000))
        assertFalse(d.onSample(700, 100))
        assertFalse(d.onSample(800, 20_000))
        assertFalse(d.onSample(900, 100))
    }

    @Test
    fun loudCalibrationBleedIsBoundedSoSpeechStaysDetectable() {
        val d = detector()
        d.onSample(0, 15_000) // loud TTS onset during grace
        assertEquals(30_000, d.effectiveTriggerAmplitude)
        assertFalse(d.onSample(600, 31_000))
        assertFalse(d.onSample(700, 31_000))
        assertTrue(d.onSample(800, 31_000))
    }

    @Test
    fun silenceFloorTracksAmbientWithinBounds() {
        val quiet = detector().apply { onSample(0, 100) }
        assertEquals(400, quiet.silenceFloorAmplitude)
        val noisy = detector().apply { onSample(0, 2_000) }
        assertEquals(3_500, noisy.silenceFloorAmplitude)
        val loud = detector().apply { onSample(0, 20_000) }
        assertEquals(6_000, loud.silenceFloorAmplitude)
    }

    @Test
    fun playbackBleedCalibratesTheTripPointAboveItself() {
        val d = detector()
        d.onSample(0, 100) // quiet ambient calibration
        // Playback begins; its first 500ms are grace + bleed calibration.
        assertFalse(d.onSample(1_000, 7_000, playbackActive = true))
        assertFalse(d.onSample(1_200, 7_000, playbackActive = true))
        assertFalse(d.onSample(1_400, 7_000, playbackActive = true))
        // Trip point is now above the measured bleed (7000 × 1.5 = 10500).
        assertEquals(10_500, d.playbackTriggerAmplitude)
        // Continued bleed at the same level never triggers.
        for (i in 0..30) {
            assertFalse(d.onSample(1_600L + i * 100, 7_000, playbackActive = true))
        }
        // Real speech over playback (louder than bleed) still barges after ~500ms.
        var triggered = false
        for (i in 0..10) {
            if (d.onSample(5_000L + i * 100, 11_500, playbackActive = true)) triggered = true
        }
        assertTrue(triggered)
    }

    @Test
    fun quietPlaybackStillUsesTheMinimumClamp() {
        val d = detector()
        d.onSample(0, 100)
        // Very quiet playback bleed (1000) — clamp floor stays at the minimum.
        assertFalse(d.onSample(1_000, 1_000, playbackActive = true))
        assertFalse(d.onSample(1_300, 1_000, playbackActive = true))
        assertEquals(4_600, d.playbackTriggerAmplitude)
        // Sub-minimum sound during playback never triggers.
        for (i in 0..20) {
            assertFalse(d.onSample(1_600L + i * 100, 4_000, playbackActive = true))
        }
    }

    @Test
    fun triggerAmplitudeScalesFloorByServerMultiplier() {
        assertEquals(600, VoiceServerConfig.DEFAULT.bargeTriggerAmplitude())
        // A zero silence floor still yields a positive trip point.
        val zeroFloor = VoiceServerConfig.DEFAULT.copy(silenceThreshold = 0)
        assertTrue(zeroFloor.bargeTriggerAmplitude() >= 1)
        // The trip point never exceeds the 16-bit amplitude ceiling.
        val loud = VoiceServerConfig.DEFAULT.copy(
            silenceThreshold = 32_000,
            bargeInThresholdMultiplier = 20.0,
        )
        assertEquals(32_767, loud.bargeTriggerAmplitude())
    }
}
