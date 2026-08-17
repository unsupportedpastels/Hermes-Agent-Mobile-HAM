package com.unsupportedpastels.hermesandroid.voice

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class VoiceConversationServiceTest {
    @After
    fun tearDown() {
        VoiceServiceBridge.onStopRequested = null
        VoiceServiceBridge.running = false
    }

    @Test
    fun startCommandMarksServiceRunning() {
        val controller = Robolectric.buildService(VoiceConversationService::class.java)
        val service = controller.create().get()
        val intent = Intent(service, VoiceConversationService::class.java)
            .putExtra("state", "Listening")
        service.onStartCommand(intent, 0, 1)
        assertTrue(VoiceServiceBridge.running)
        controller.destroy()
        assertFalse(VoiceServiceBridge.running)
    }

    @Test
    fun notificationStopActionEndsLoopThroughBridge() {
        var loopStopped = false
        VoiceServiceBridge.onStopRequested = { loopStopped = true }
        val controller = Robolectric.buildService(VoiceConversationService::class.java)
        val service = controller.create().get()
        // Arm, then deliver the notification's Stop action.
        service.onStartCommand(
            Intent(service, VoiceConversationService::class.java).putExtra("state", "Speaking"),
            0,
            1,
        )
        service.onStartCommand(
            Intent(service, VoiceConversationService::class.java)
                .setAction("com.unsupportedpastels.hermesandroid.voice.STOP"),
            0,
            2,
        )
        assertTrue(loopStopped)
        assertFalse(VoiceServiceBridge.running)
        controller.destroy()
    }
}
