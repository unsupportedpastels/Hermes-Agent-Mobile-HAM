package com.unsupportedpastels.hermesandroid.voice

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Device capability/result for the capture session used by full-duplex barge
 * monitoring. A VOICE_COMMUNICATION source can select a platform preprocessor,
 * but only the attached effect/session state proves that AEC is active.
 */
data class VoiceAudioProcessingStatus(
    val audioSessionId: Int,
    val acousticEchoCancelerAvailable: Boolean,
    val acousticEchoCancelerEnabled: Boolean,
    val noiseSuppressorAvailable: Boolean,
    val noiseSuppressorEnabled: Boolean,
)

/**
 * AudioRecord-backed barge recorder with explicit platform AEC/NS attachment.
 * The recorder continuously drains PCM on a reader thread so amplitude samples
 * and the final WAV clip come from the same post-processed capture path.
 */
class AudioRecordBargeRecorder(
    private val context: Context,
    private val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    private val onDiagnostics: (String) -> Unit = {},
) : DictationRecorder {
    override val mimeType: String = "audio/wav"

    @Volatile
    var processingStatus: VoiceAudioProcessingStatus? = null
        private set

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var readerThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val peakAmplitude = AtomicInteger(0)
    private val pcm = ByteArrayOutputStream()

    override fun start(): Boolean {
        cancel()
        synchronized(pcm) { pcm.reset() }
        peakAmplitude.set(0)

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            onDiagnostics("audio:aec-recorder-unavailable reason=permission-denied")
            return false
        }

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minimumBuffer = AudioRecord.getMinBufferSize(sampleRateHz, channelConfig, audioFormat)
        if (minimumBuffer <= 0) {
            onDiagnostics("audio:aec-recorder-unavailable reason=invalid-buffer")
            return false
        }

        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(channelConfig)
                        .build(),
                )
                .setBufferSizeInBytes((minimumBuffer * 2).coerceAtLeast(sampleRateHz / 2))
                .build()
        } catch (_: Exception) {
            onDiagnostics("audio:aec-recorder-unavailable reason=construct-failed")
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onDiagnostics("audio:aec-recorder-unavailable reason=not-initialized")
            return false
        }

        val sessionId = record.audioSessionId
        val aecAvailable = AcousticEchoCanceler.isAvailable()
        val nsAvailable = NoiseSuppressor.isAvailable()
        val aec = if (aecAvailable) runCatching {
            AcousticEchoCanceler.create(sessionId)
        }.getOrNull() else null
        val ns = if (nsAvailable) runCatching {
            NoiseSuppressor.create(sessionId)
        }.getOrNull() else null

        val aecEnabled = aec?.let { runCatching { it.enabled = true }.isSuccess && it.enabled } == true
        val nsEnabled = ns?.let { runCatching { it.enabled = true }.isSuccess && it.enabled } == true
        processingStatus = VoiceAudioProcessingStatus(
            audioSessionId = sessionId,
            acousticEchoCancelerAvailable = aecAvailable,
            acousticEchoCancelerEnabled = aecEnabled,
            noiseSuppressorAvailable = nsAvailable,
            noiseSuppressorEnabled = nsEnabled,
        )
        onDiagnostics(
            "audio:aec-status session=$sessionId " +
                "aecAvailable=$aecAvailable aecEnabled=$aecEnabled " +
                "nsAvailable=$nsAvailable nsEnabled=$nsEnabled",
        )

        return try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("recording-not-started")
            }
            audioRecord = record
            echoCanceler = aec
            noiseSuppressor = ns
            running.set(true)
            readerThread = Thread({ readLoop(record) }, "hermes-aec-capture").apply {
                isDaemon = true
                start()
            }
            onDiagnostics("audio:aec-capture-started rate=$sampleRateHz")
            true
        } catch (_: Exception) {
            runCatching { ns?.release() }
            runCatching { aec?.release() }
            runCatching { record.release() }
            processingStatus = null
            onDiagnostics("audio:aec-recorder-unavailable reason=start-failed")
            false
        }
    }

    override fun sampleAmplitude(): Int = peakAmplitude.getAndSet(0)

    override fun sampleLevel(): Float =
        (sampleAmplitude() / MAX_AMPLITUDE).coerceIn(0f, 1f)

    override fun stopAndEncode(): DictationRecording? {
        stopCapture()
        val pcmBytes = synchronized(pcm) { pcm.toByteArray() }
        if (pcmBytes.isEmpty()) return null
        return DictationRecording(
            dataUrl = encodeAudioDataUrl(pcm16MonoToWav(pcmBytes, sampleRateHz), mimeType),
            mimeType = mimeType,
        )
    }

    override fun cancel() {
        stopCapture()
        synchronized(pcm) { pcm.reset() }
        peakAmplitude.set(0)
    }

    private fun readLoop(record: AudioRecord) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (running.get()) {
            val count = try {
                record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            } catch (_: Exception) {
                break
            }
            if (count <= 0) continue
            synchronized(pcm) { pcm.write(buffer, 0, count) }
            val peak = pcmPeakAmplitude(buffer, count)
            if (peak > 0) peakAmplitude.getAndUpdate { current -> maxOf(current, peak) }
        }
    }

    private fun stopCapture() {
        running.set(false)
        audioRecord?.let { runCatching { it.stop() } }
        readerThread?.let { thread ->
            runCatching { thread.join(READER_JOIN_TIMEOUT_MILLIS) }
        }
        readerThread = null
        runCatching { noiseSuppressor?.release() }
        runCatching { echoCanceler?.release() }
        noiseSuppressor = null
        echoCanceler = null
        audioRecord?.let { runCatching { it.release() } }
        audioRecord = null
    }

    private companion object {
        const val DEFAULT_SAMPLE_RATE_HZ = 16_000
        const val BUFFER_BYTES = 3_200
        const val READER_JOIN_TIMEOUT_MILLIS = 250L
        const val MAX_AMPLITUDE = 32_767f
    }
}

private fun pcmPeakAmplitude(bytes: ByteArray, length: Int): Int {
    var peak = 0
    var index = 0
    while (index + 1 < length) {
        val unsigned = (bytes[index].toInt() and 0xff) or
            ((bytes[index + 1].toInt() and 0xff) shl 8)
        val signed = if (unsigned > Short.MAX_VALUE) unsigned - (1 shl 16) else unsigned
        peak = maxOf(peak, abs(signed))
        index += 2
    }
    return peak
}

/** Wrap little-endian mono PCM16 bytes in a bounded RIFF/WAV container. */
internal fun pcm16MonoToWav(pcm: ByteArray, sampleRateHz: Int): ByteArray {
    val byteRate = sampleRateHz * 2
    return ByteBuffer.allocate(44 + pcm.size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply {
            put("RIFF".toByteArray())
            putInt(36 + pcm.size)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRateHz)
            putInt(byteRate)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(pcm.size)
            put(pcm)
        }
        .array()
}
