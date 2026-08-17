package com.unsupportedpastels.hermesandroid.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceServerConfigTest {
    private fun parse(raw: String): VoiceServerConfig =
        VoiceServerConfig.fromConfigRoot(Json.parseToJsonElement(raw) as? JsonObject)

    @Test
    fun absentVoiceSectionYieldsDefaults() {
        assertEquals(VoiceServerConfig.DEFAULT, parse("""{"agent":{}}"""))
    }

    @Test
    fun malformedVoiceSectionYieldsDefaults() {
        // Server tolerates `voice` being a bool/str instead of a dict (voice.py).
        assertEquals(VoiceServerConfig.DEFAULT, parse("""{"voice": true}"""))
        assertEquals(VoiceServerConfig.DEFAULT, parse("""{"voice": "en-US-AriaNeural"}"""))
    }

    @Test
    fun defaultsMatchReleasedServerDefaults() {
        val defaults = VoiceServerConfig.DEFAULT
        assertEquals(VoiceSubmitMode.Direct, defaults.submitMode)
        assertEquals(120, defaults.maxRecordingSeconds)
        assertEquals(false, defaults.autoTts)
        assertEquals(200, defaults.silenceThreshold)
        assertEquals(3.0, defaults.silenceDurationSeconds, 0.0)
        assertTrue(defaults.bargeInEnabled)
        assertEquals(0.5, defaults.bargeInGraceSeconds, 0.0)
        assertEquals(3.0, defaults.bargeInThresholdMultiplier, 0.0)
        assertEquals(listOf("stop"), defaults.stopPhrases)
    }

    @Test
    fun fullSectionParsesEveryField() {
        val config = parse(
            """
            {
              "voice": {
                "submit_mode": "draft",
                "max_recording_seconds": 60,
                "auto_tts": true,
                "silence_threshold": 400,
                "silence_duration": 2.5,
                "barge_in": false,
                "barge_in_grace_seconds": 0.75,
                "barge_in_threshold_multiplier": 4.0,
                "stop_phrases": ["Stop", "cancel", "STOP"]
              }
            }
            """.trimIndent(),
        )
        assertEquals(VoiceSubmitMode.Draft, config.submitMode)
        assertEquals(60, config.maxRecordingSeconds)
        assertTrue(config.autoTts)
        assertEquals(400, config.silenceThreshold)
        assertEquals(2.5, config.silenceDurationSeconds, 0.0)
        assertEquals(false, config.bargeInEnabled)
        assertEquals(0.75, config.bargeInGraceSeconds, 0.0)
        assertEquals(4.0, config.bargeInThresholdMultiplier, 0.0)
        // Lower-cased and de-duplicated.
        assertEquals(listOf("stop", "cancel"), config.stopPhrases)
    }

    @Test
    fun submitModeIsCaseInsensitiveAndFallsBackToDirect() {
        assertEquals(VoiceSubmitMode.Draft, VoiceSubmitMode.parse("DRAFT"))
        assertEquals(VoiceSubmitMode.Direct, VoiceSubmitMode.parse("direct"))
        assertEquals(VoiceSubmitMode.Direct, VoiceSubmitMode.parse("nonsense"))
        assertEquals(VoiceSubmitMode.Direct, VoiceSubmitMode.parse(null))
    }

    @Test
    fun outOfRangeNumbersAreCoercedIntoSafeBounds() {
        val config = parse(
            """
            {"voice": {"max_recording_seconds": 100000, "silence_threshold": -5,
             "barge_in_threshold_multiplier": 0.1}}
            """.trimIndent(),
        )
        assertEquals(600, config.maxRecordingSeconds)
        assertEquals(0, config.silenceThreshold)
        assertEquals(1.0, config.bargeInThresholdMultiplier, 0.0)
    }

    @Test
    fun wrongTypedFieldsFallBackPerField() {
        // A single mistyped field must not discard the rest of the section.
        val config = parse(
            """{"voice": {"max_recording_seconds": "lots", "auto_tts": true}}""",
        )
        assertEquals(VoiceServerConfig.DEFAULT.maxRecordingSeconds, config.maxRecordingSeconds)
        assertTrue(config.autoTts)
    }

    @Test
    fun emptyStopPhrasesListDisablesStopWords() {
        val config = parse("""{"voice": {"stop_phrases": []}}""")
        assertEquals(emptyList<String>(), config.stopPhrases)
    }
}
