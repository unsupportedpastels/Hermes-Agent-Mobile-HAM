package com.unsupportedpastels.hermesandroid.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SilenceAutoStopTest {
    private fun autoStop(
        configured: Int = 200,
        silenceMillis: Long = 1_000L,
        maxMillis: Long = 120_000L,
        noSpeechMillis: Long = 8_000L,
    ) = SilenceAutoStop(
        configuredThreshold = configured,
        silenceMillis = silenceMillis,
        maxMillis = maxMillis,
        noSpeechTimeoutMillis = noSpeechMillis,
    )

    /** Drive with 100ms samples; returns the first non-Continue decision and its time. */
    private fun run(
        detector: SilenceAutoStop,
        samples: List<Int>,
    ): Pair<SilenceAutoStopDecision, Long>? {
        samples.forEachIndexed { index, amplitude ->
            val elapsed = index * 100L
            val decision = detector.feed(elapsed, amplitude)
            if (decision != SilenceAutoStopDecision.Continue) return decision to elapsed
        }
        return null
    }

    @Test
    fun speechThenSilenceStopsDespiteNoisyAmbientFloor() {
        // Ambient peaks ~1500 — far above the configured 200 — then speech at
        // ~15000, then back to ambient. The old raw comparison never stopped.
        val ambient = List(6) { 1_500 }
        val speech = List(10) { 15_000 }
        val quiet = List(15) { 1_500 }
        val result = run(autoStop(), ambient + speech + quiet)
        assertEquals(SilenceAutoStopDecision.Stop, result?.first)
    }

    @Test
    fun quietRoomSpeechEndsAfterConfiguredSilence() {
        val ambient = List(6) { 100 }
        val speech = List(5) { 12_000 }
        val quiet = List(12) { 100 }
        val result = run(autoStop(), ambient + speech + quiet)
        assertEquals(SilenceAutoStopDecision.Stop, result?.first)
        // Silence run: 1s after the speech ends (plus one sample of slack).
        val stoppedAt = result!!.second
        val speechEndedAt = (6 + 5) * 100L
        assert(stoppedAt - speechEndedAt in 900..1_200) { "stopped at $stoppedAt" }
    }

    @Test
    fun pureAmbientNoiseResolvesEmptyWithoutUpload() {
        val result = run(autoStop(noSpeechMillis = 2_000L), List(40) { 1_500 })
        assertEquals(SilenceAutoStopDecision.StopEmpty, result?.first)
        assertEquals(2_000L, result?.second)
    }

    @Test
    fun intraSentencePausesShorterThanSilenceWindowDoNotStop() {
        val ambient = List(6) { 800 }
        val phrase1 = List(8) { 14_000 }
        val shortPause = List(5) { 800 } // 500ms < 1s window
        val phrase2 = List(8) { 14_000 }
        val quiet = List(15) { 800 }
        val result = run(autoStop(), ambient + phrase1 + shortPause + phrase2 + quiet)
        assertEquals(SilenceAutoStopDecision.Stop, result?.first)
        // Must stop after phrase 2, not inside the short pause.
        val stopAt = result!!.second
        assert(stopAt >= (6 + 8 + 5 + 8) * 100L) { "stopped too early at $stopAt" }
    }

    @Test
    fun roomToneStraddlingConfiguredFloorStillStops() {
        // Regression: real device trace (SM-F971U1). Warm-up calibration reads
        // ambient=86 so the floor stays at the configured 200, while true room
        // tone bounces 190–340 with occasional ~2–3k blips. A fixed-floor reset
        // rule never accumulated the 3s of silence.
        val detector = autoStop(configured = 200, silenceMillis = 3_000L)
        val calibration = List(6) { 86 }
        val speech = List(15) { 11_765 }
        val roomTone = listOf(
            331, 309, 197, 261, 232, 314, 190, 340, 1_909, 239,
            232, 197, 261, 340, 3_128, 195, 261, 232, 314, 190,
            197, 261, 232, 314, 190, 340, 239, 232, 197, 261,
            340, 195, 261, 232, 314, 190, 197, 261, 232, 314,
            190, 340, 239, 232, 197, 261, 340, 195, 261, 232,
        )
        val result = run(detector, calibration + speech + roomTone)
        assertEquals(SilenceAutoStopDecision.Stop, result?.first)
        // Stops within roughly the configured window plus blip drain, not never.
        assert(result!!.second <= (6 + 15 + 40) * 100L) { "stopped at ${result.second}" }
    }

    @Test
    fun recordingCapAlwaysStops() {
        val result = run(
            autoStop(maxMillis = 1_000L),
            List(40) { 20_000 }, // continuous speech
        )
        assertEquals(SilenceAutoStopDecision.Stop, result?.first)
        assertEquals(1_000L, result?.second)
    }

    @Test
    fun loudRoomFloorIsBoundedSoSpeechStaysDetectable() {
        // Calibration sees very loud ambient (e.g. music at 10k). The floor is
        // clamped, so 20k speech still registers and its end still stops.
        val ambient = List(6) { 10_000 }
        val speech = List(8) { 25_000 }
        val quiet = List(15) { 3_000 }
        val result = run(autoStop(), ambient + speech + quiet)
        assertEquals(SilenceAutoStopDecision.Stop, result?.first)
    }
}
