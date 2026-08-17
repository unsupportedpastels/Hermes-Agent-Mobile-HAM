package com.unsupportedpastels.hermesandroid.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceStopPhrasesTest {
    private val phrases = listOf("stop", "never mind", "goodbye")

    @Test
    fun matchesExactPhrase() {
        assertTrue(isVoiceStopPhrase("stop", phrases))
        assertTrue(isVoiceStopPhrase("never mind", phrases))
        assertTrue(isVoiceStopPhrase("goodbye", phrases))
    }

    @Test
    fun matchesDespiteCaseAndSurroundingPunctuation() {
        assertTrue(isVoiceStopPhrase("Stop.", phrases))
        assertTrue(isVoiceStopPhrase("  STOP!  ", phrases))
        assertTrue(isVoiceStopPhrase("\"stop\"", phrases))
        assertTrue(isVoiceStopPhrase("never mind...", phrases))
    }

    @Test
    fun doesNotMatchSubstantiveSentencesContainingThePhrase() {
        assertFalse(isVoiceStopPhrase("stop the container", phrases))
        assertFalse(isVoiceStopPhrase("how do I stop a process", phrases))
        assertFalse(isVoiceStopPhrase("stop doing that and try again", phrases))
    }

    @Test
    fun emptyAndPunctuationOnlyUtterancesNeverMatch() {
        assertFalse(isVoiceStopPhrase("", phrases))
        assertFalse(isVoiceStopPhrase("   ", phrases))
        assertFalse(isVoiceStopPhrase("...", phrases))
    }

    @Test
    fun emptyPhraseListDisablesSpokenStop() {
        assertFalse(isVoiceStopPhrase("stop", emptyList()))
    }
}
