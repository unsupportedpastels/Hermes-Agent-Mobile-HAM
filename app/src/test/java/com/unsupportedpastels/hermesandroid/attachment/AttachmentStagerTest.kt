package com.unsupportedpastels.hermesandroid.attachment

import com.unsupportedpastels.hermesandroid.app.ComposerAttachment
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.gateway.HermesChatEvent
import com.unsupportedpastels.hermesandroid.gateway.HermesChatSession
import com.unsupportedpastels.hermesandroid.gateway.InflightPrompt
import com.unsupportedpastels.hermesandroid.gateway.PromptSubmission
import com.unsupportedpastels.hermesandroid.gateway.ResumedChatSession
import com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class AttachmentStagerTest {

    private val runtimeId = RuntimeSessionId("runtime-1")

    private class RecordingSession : HermesChatSession {
        val imageCalls = mutableListOf<Pair<String, String>>()
        val fileCalls = mutableListOf<Triple<String, String, String>>()

        override val events: Flow<HermesChatEvent> = emptyFlow()

        override suspend fun resume(
            durableSessionId: DurableSessionId,
            profile: String?,
        ): ResumedChatSession = throw UnsupportedOperationException("not used")

        override suspend fun submitPrompt(
            runtimeSessionId: RuntimeSessionId,
            text: String,
        ): PromptSubmission = throw UnsupportedOperationException("not used")

        override suspend fun attachFile(
            runtimeSessionId: RuntimeSessionId,
            filename: String,
            mimeType: String,
            base64Content: String,
        ): String {
            fileCalls += Triple(filename, mimeType, base64Content)
            return "@file:.hermes/desktop-attachments/$filename"
        }

        override suspend fun attachImage(
            runtimeSessionId: RuntimeSessionId,
            filename: String,
            base64Content: String,
        ) {
            imageCalls += filename to base64Content
        }

        override suspend fun close() = Unit
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun stagesImagesAndFilesThroughTheirRPCs() = runTest {
        val session = RecordingSession()
        val reader = AttachmentByteReader { attachment ->
            when (attachment.displayName) {
                "photo.png" -> "pngbytes".toByteArray()
                "report.txt" -> "hello".toByteArray()
                else -> error("unexpected attachment")
            }
        }

        val staged = AttachmentStager(session, runtimeId, reader).stage(
            listOf(
                ComposerAttachment("1", "content://provider/photo", "photo.png", "image/png", 8),
                ComposerAttachment("2", "content://provider/report", "report.txt", "text/plain", 5),
            ),
        )

        assertEquals(listOf("@file:.hermes/desktop-attachments/report.txt"), staged.refTexts)
        assertEquals(listOf("photo.png"), staged.names)
        assertEquals(listOf("photo.png" to encode("pngbytes".toByteArray())), session.imageCalls)
        assertEquals(
            listOf(Triple("report.txt", "text/plain", encode("hello".toByteArray()))),
            session.fileCalls,
        )
    }

    @Test
    fun oversizedReadIsRejectedEvenWhenTheReaderPassedIt() = runTest {
        val session = RecordingSession()
        val reader = AttachmentByteReader { ByteArray((AttachmentPolicy.MAX_FILE_BYTES + 1).toInt()) }

        val error = runCatching {
            AttachmentStager(session, runtimeId, reader).stage(
                listOf(
                    ComposerAttachment("1", "content://provider/big", "big.bin", "application/octet-stream", -1),
                ),
            )
        }.exceptionOrNull()

        assertTrue(error is AttachmentTooLargeException)
        assertTrue(session.fileCalls.isEmpty())
        assertTrue(session.imageCalls.isEmpty())
    }

    @Test
    fun readerFailurePropagatesWithoutStagingAnything() = runTest {
        val session = RecordingSession()
        val reader = AttachmentByteReader { throw AttachmentReadException("could not open") }

        val error = runCatching {
            AttachmentStager(session, runtimeId, reader).stage(
                listOf(
                    ComposerAttachment("1", "content://provider/photo", "photo.png", "image/png", 8),
                ),
            )
        }.exceptionOrNull()

        assertTrue(error is AttachmentReadException)
        assertTrue(session.imageCalls.isEmpty())
        assertTrue(session.fileCalls.isEmpty())
    }

    @Test
    fun stagesNothingWhenListIsEmpty() = runTest {
        val session = RecordingSession()
        val staged = AttachmentStager(session, runtimeId, AttachmentByteReader { error("no reads expected") })
            .stage(emptyList())
        assertEquals(emptyList<String>(), staged.refTexts)
        assertEquals(emptyList<String>(), staged.names)
    }

    @Test
    fun aggregateOverflowIsRejectedBeforeTheFirstRpc() = runTest {
        val session = RecordingSession()
        val bytesPerImage = 16 * 1024 * 1024
        val reader = AttachmentByteReader { ByteArray(bytesPerImage) }
        val attachments = listOf(
            ComposerAttachment("1", "content://provider/one", "one.png", "image/png", -1),
            ComposerAttachment("2", "content://provider/two", "two.png", "image/png", -1),
        )

        val error = runCatching {
            AttachmentStager(session, runtimeId, reader).stage(attachments)
        }.exceptionOrNull()

        assertTrue(error is AttachmentTooLargeException)
        assertTrue(session.imageCalls.isEmpty())
        assertTrue(session.fileCalls.isEmpty())
    }
}
