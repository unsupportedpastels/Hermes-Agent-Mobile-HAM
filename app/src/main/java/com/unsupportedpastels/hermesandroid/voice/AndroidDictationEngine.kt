package com.unsupportedpastels.hermesandroid.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.util.Base64

/** A finished recording, ready to POST to `/api/audio/transcribe`. */
data class DictationRecording(val dataUrl: String, val mimeType: String)

/**
 * Captures a single dictation utterance. Implementations are Android-backed
 * ([MediaRecorderDictationRecorder]); the polling/silence/elapsed logic that
 * drives [DictationController] lives in the composable host so it stays testable.
 */
interface DictationRecorder {
    /** MIME type the encoded recording will carry. */
    val mimeType: String

    /** Begin capture. Returns false if the recorder could not start. */
    fun start(): Boolean

    /** Peak amplitude since the previous sample, normalised to 0..1. */
    fun sampleLevel(): Float

    /** Peak amplitude since the previous sample on the raw 0..32767 scale (for silence checks). */
    fun sampleAmplitude(): Int

    /** Stop capture and encode the recording, or null if nothing was captured. */
    fun stopAndEncode(): DictationRecording?

    /** Discard the in-progress recording and release resources. */
    fun cancel()
}

/**
 * Build a `data:<mime>;base64,<...>` URL from raw audio bytes. Pure (JVM
 * Base64) so it is unit-testable without a device.
 */
fun encodeAudioDataUrl(bytes: ByteArray, mimeType: String): String {
    val encoded = Base64.getEncoder().encodeToString(bytes)
    return "data:$mimeType;base64,$encoded"
}

private const val MAX_AMPLITUDE = 32_767f

/**
 * [MediaRecorder]-backed recorder producing an AAC/MPEG-4 (`audio/mp4`) clip in
 * the app cache. Mirrors [com.unsupportedpastels.hermesandroid.ui] audio
 * conventions: a dedicated cache subdir and best-effort temp-file cleanup.
 */
class MediaRecorderDictationRecorder(
    private val context: Context,
) : DictationRecorder {
    override val mimeType: String = "audio/mp4"

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

    override fun start(): Boolean {
        cancel()
        return try {
            val directory = File(context.cacheDir, "dictation").apply { mkdirs() }
            val file = File.createTempFile("dictation-", ".m4a", directory)
            val recorder = newRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            this.recorder = recorder
            this.outputFile = file
            true
        } catch (_: Exception) {
            cancel()
            false
        }
    }

    override fun sampleAmplitude(): Int =
        try {
            recorder?.maxAmplitude ?: 0
        } catch (_: Exception) {
            0
        }

    override fun sampleLevel(): Float = (sampleAmplitude() / MAX_AMPLITUDE).coerceIn(0f, 1f)

    override fun stopAndEncode(): DictationRecording? {
        val recorder = this.recorder
        val file = this.outputFile
        this.recorder = null
        this.outputFile = null
        return try {
            recorder?.apply {
                stop()
                release()
            }
            val bytes = file?.takeIf { it.exists() }?.readBytes()
            if (bytes == null || bytes.isEmpty()) {
                null
            } else {
                DictationRecording(encodeAudioDataUrl(bytes, mimeType), mimeType)
            }
        } catch (_: Exception) {
            null
        } finally {
            file?.delete()
        }
    }

    override fun cancel() {
        try {
            recorder?.apply {
                runCatching { stop() }
                release()
            }
        } catch (_: Exception) {
            // A recorder that never started throws on stop(); release is best-effort.
        } finally {
            recorder = null
            outputFile?.delete()
            outputFile = null
        }
    }
}
