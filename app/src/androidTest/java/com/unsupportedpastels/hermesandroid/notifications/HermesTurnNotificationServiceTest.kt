package com.unsupportedpastels.hermesandroid.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import org.junit.After
import org.junit.Assert.assertTrue

import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HermesTurnNotificationServiceTest {
    private val context = androidx.test.platform.app.InstrumentationRegistry
        .getInstrumentation()
        .targetContext

    @After
    fun stopService() {
        context.stopService(Intent(context, HermesTurnNotificationService::class.java))
    }

    @Test
    fun twoActiveTurnsProduceAggregateForegroundNotificationThenFinalPreview() {
        if (Build.VERSION.SDK_INT >= 33) {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val connected = java.util.concurrent.CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) = connected.countDown()
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        context.bindService(
            Intent(context, HermesTurnNotificationService::class.java),
            connection,
            android.content.Context.BIND_AUTO_CREATE,
        )
        assertTrue(connected.await(5, java.util.concurrent.TimeUnit.SECONDS))
        context.startService(Intent(context, HermesTurnNotificationService::class.java).apply {
            action = HermesTurnNotificationService.ACTION_START
            putExtra(HermesTurnNotificationService.EXTRA_COUNT, 2)
        })
        waitForNotificationText("Hermes is working in 2 sessions")

        SessionNotificationPoster.postCompletion(
            context,
            DurableSessionId("session-a"),
            title = "Session A",
            text = "# Result\n\n**First session finished.**\nAll checks pass.",
            status = "complete",
        )
        waitForNotificationText("First session finished.")
        context.unbindService(connection)
    }

    private fun waitForNotificationText(expected: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val found = manager.activeNotifications.any { posted ->
                val extras = posted.notification.extras
                listOf(
                    extras.getCharSequence("android.title"),
                    extras.getCharSequence("android.text"),
                    extras.getCharSequence("android.bigText"),
                ).any { it?.contains(expected) == true }
            }
            if (found) return
            Thread.sleep(50)
        }
        assertTrue("Missing notification text: $expected", false)
    }
}
