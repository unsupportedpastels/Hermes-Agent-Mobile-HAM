package com.unsupportedpastels.hermesandroid

import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MainActivityVoicePreferenceTest {
    @Test
    fun screenOffConsentKeyUsesNormalizedServerOrigin() {
        val first = voiceScreenOffPreferenceKey(ServerOrigin.parse("HTTPS://one.example/"))
        val equivalent = voiceScreenOffPreferenceKey(ServerOrigin.parse("https://one.example"))
        val second = voiceScreenOffPreferenceKey(ServerOrigin.parse("https://two.example"))

        assertEquals(first, equivalent)
        assertNotEquals(first, second)
        assertNotEquals(first, voiceScreenOffPreferenceKey(null))
    }
}