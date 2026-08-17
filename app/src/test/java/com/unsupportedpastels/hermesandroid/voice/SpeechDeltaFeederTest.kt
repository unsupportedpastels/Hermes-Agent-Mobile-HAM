package com.unsupportedpastels.hermesandroid.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechDeltaFeederTest {
    @Test
    fun emitsAppendOnlyDeltasPreservingWhitespace() {
        val feeder = SpeechDeltaFeeder()
        assertEquals("Hello", feeder.nextDelta("Hello"))
        assertEquals(" world.\n", feeder.nextDelta("Hello world.\n"))
        assertNull(feeder.nextDelta("Hello world.\n"))
    }

    @Test
    fun replacementPausesFeedingUntilFinal() {
        val feeder = SpeechDeltaFeeder()
        assertEquals("Hello wor", feeder.nextDelta("Hello wor"))
        // The message was rewritten — not an extension of what was spoken.
        assertNull(feeder.nextDelta("Goodbye"))
        // Later extensions stay paused too; no duplicated speech.
        assertNull(feeder.nextDelta("Goodbye friend"))
    }

    @Test
    fun finalReconciliationFeedsCleanSuffix() {
        val feeder = SpeechDeltaFeeder()
        feeder.nextDelta("Hello")
        assertEquals(" world.", feeder.reconcileFinal("Hello world."))
    }

    @Test
    fun unreconcilableFinalFeedsNothing() {
        val feeder = SpeechDeltaFeeder()
        feeder.nextDelta("Hello world")
        assertNull(feeder.reconcileFinal("Completely different reply."))
    }

    @Test
    fun finalIdenticalToFedTextFeedsNothing() {
        val feeder = SpeechDeltaFeeder()
        feeder.nextDelta("Hello world.")
        assertNull(feeder.reconcileFinal("Hello world."))
    }

    @Test
    fun emptyStreamThenFinalFeedsWholeReply() {
        val feeder = SpeechDeltaFeeder()
        assertEquals("Full reply at once.", feeder.reconcileFinal("Full reply at once."))
    }
}
