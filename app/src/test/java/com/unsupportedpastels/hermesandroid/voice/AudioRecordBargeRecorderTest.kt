package com.unsupportedpastels.hermesandroid.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class AudioRecordBargeRecorderTest {
    @Test
    fun pcmIsWrappedAsMonoPcm16Wave() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val wav = pcm16MonoToWav(pcm, sampleRateHz = 16_000)
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(48, wav.size)
        assertArrayEquals("RIFF".toByteArray(), wav.copyOfRange(0, 4))
        assertEquals(36 + pcm.size, header.getInt(4))
        assertArrayEquals("WAVE".toByteArray(), wav.copyOfRange(8, 12))
        assertEquals(16_000, header.getInt(24))
        assertEquals(32_000, header.getInt(28))
        assertEquals(4, header.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }
}
