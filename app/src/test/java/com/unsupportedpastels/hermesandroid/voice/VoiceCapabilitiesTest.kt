package com.unsupportedpastels.hermesandroid.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCapabilitiesTest {
    @Test
    fun okProbeWithKeyEnablesEverythingIncludingPicker() {
        val caps = VoiceCapabilityPolicy.fromVoicesProbe(200, elevenLabsAvailable = true)
        assertTrue(caps.audioRoutesPresent)
        assertTrue(caps.canDictateViaServer)
        assertTrue(caps.canReadAloud)
        assertTrue(caps.canStreamSpeech)
        assertTrue(caps.canPickElevenLabsVoice)
    }

    @Test
    fun okProbeWithoutKeyKeepsAudioButHidesPicker() {
        val caps = VoiceCapabilityPolicy.fromVoicesProbe(200, elevenLabsAvailable = false)
        assertTrue(caps.audioRoutesPresent)
        assertTrue(caps.canReadAloud)
        assertFalse(caps.canPickElevenLabsVoice)
    }

    @Test
    fun routeNotFoundHidesAllVoice() {
        for (code in intArrayOf(404, 405)) {
            val caps = VoiceCapabilityPolicy.fromVoicesProbe(code, elevenLabsAvailable = true)
            assertEquals(VoiceCapabilities.NONE, caps)
            assertFalse(caps.canReadAloud)
            assertFalse(caps.canDictateViaServer)
        }
    }

    @Test
    fun authOrServerErrorsFailClosed() {
        for (code in intArrayOf(401, 403, 500, 502, 503)) {
            assertEquals(VoiceCapabilities.NONE, VoiceCapabilityPolicy.fromVoicesProbe(code, true))
        }
    }
}
