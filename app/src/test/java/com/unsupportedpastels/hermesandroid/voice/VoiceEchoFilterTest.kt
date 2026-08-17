package com.unsupportedpastels.hermesandroid.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceEchoFilterTest {
    private val reply =
        "The build finished successfully. All forty-two tests passed and the " +
            "artifact was uploaded to the staging server for review."

    @Test
    fun verbatimSelfCaptureIsEcho() {
        assertTrue(VoiceEchoFilter.isTtsEcho(reply, reply))
    }

    @Test
    fun fragmentOfLongerReplyIsEcho() {
        // The barge capture spans only the pre-roll plus time-to-silence — a
        // clause from the middle of the reply, slightly mis-transcribed.
        assertTrue(
            VoiceEchoFilter.isTtsEcho(
                "all forty two tests passed and the artifact was uploaded",
                reply,
            ),
        )
    }

    @Test
    fun genuineInterjectionIsNotEcho() {
        assertFalse(VoiceEchoFilter.isTtsEcho("actually switch to the release build", reply))
    }

    @Test
    fun shortGenuineInterjectionSkipsWindowFallback() {
        // "yes" appears nowhere; even if a reply contained it, transcripts under
        // the fragment minimum never use the window fallback.
        assertFalse(VoiceEchoFilter.isTtsEcho("yes", reply))
        assertFalse(VoiceEchoFilter.isTtsEcho("stop that", reply))
    }

    @Test
    fun caseAndWhitespaceAreNormalized() {
        assertTrue(
            VoiceEchoFilter.isTtsEcho(
                "  THE BUILD   finished SUCCESSFULLY. all forty-two tests passed and the artifact was uploaded to the staging server for review. ",
                reply,
            ),
        )
    }

    @Test
    fun emptyInputsAreNeverEcho() {
        assertFalse(VoiceEchoFilter.isTtsEcho("", reply))
        assertFalse(VoiceEchoFilter.isTtsEcho("hello", null))
        assertFalse(VoiceEchoFilter.isTtsEcho("hello", ""))
    }
}
