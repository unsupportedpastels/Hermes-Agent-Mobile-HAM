package com.unsupportedpastels.hermesandroid.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Streams mono int16 PCM from `/api/audio/speak-stream` through an [AudioTrack]
 * in streaming mode. Created lazily on the server's start frame (which carries
 * the sample rate); writes are blocking on [ioDispatcher]; an odd trailing byte
 * is carried into the next frame so 16-bit samples never split across writes.
 */
class AudioTrackSpeechSink(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val trackFactory: (sampleRateHz: Int) -> AudioTrack = ::defaultTrack,
) : PcmSpeechSink {
    private var track: AudioTrack? = null
    private var carriedByte: Byte? = null

    override fun start(sampleRateHz: Int, channels: Int): Boolean {
        if (channels != 1) return false
        stop()
        return try {
            val created = trackFactory(sampleRateHz)
            if (created.state != AudioTrack.STATE_INITIALIZED) {
                created.release()
                return false
            }
            created.play()
            track = created
            true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun write(pcm: ByteArray) {
        val activeTrack = track ?: return
        // Re-attach a carried odd byte so samples stay 16-bit aligned.
        val carried = carriedByte
        val aligned: ByteArray
        if (carried != null) {
            aligned = ByteArray(pcm.size + 1)
            aligned[0] = carried
            pcm.copyInto(aligned, 1)
            carriedByte = null
        } else {
            aligned = pcm
        }
        val writable = aligned.size and 1.inv()
        if (writable < aligned.size) carriedByte = aligned[aligned.size - 1]
        if (writable == 0) return
        withContext(ioDispatcher) {
            var offset = 0
            while (offset < writable) {
                val written = activeTrack.write(aligned, offset, writable - offset)
                if (written <= 0) break
                offset += written
            }
        }
    }

    override suspend fun finish() {
        val activeTrack = track ?: return
        withContext(ioDispatcher) {
            try {
                activeTrack.stop()
            } catch (_: Exception) {
            }
            activeTrack.release()
        }
        track = null
        carriedByte = null
    }

    override fun stop() {
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.stop() }
            it.release()
        }
        track = null
        carriedByte = null
    }

    private companion object {
        fun defaultTrack(sampleRateHz: Int): AudioTrack {
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRateHz,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(sampleRateHz / 2)
            return AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuffer * 2)
                .build()
        }
    }
}
