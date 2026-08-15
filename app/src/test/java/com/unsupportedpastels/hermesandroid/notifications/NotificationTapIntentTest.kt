package com.unsupportedpastels.hermesandroid.notifications

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationTapIntentTest {
    @Test
    fun sessionTapTargetsPrivateActivityThatCanLaunchFromNotification() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val intent = notificationTapIntent(context, "session-1")

        assertEquals(NotificationTapActivity::class.java.name, intent.component?.className)
        assertEquals("session-1", intent.getStringExtra(EXTRA_TAP_SESSION_ID))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val activity = context.packageManager.getActivityInfo(intent.component!!, 0)
        assertFalse(activity.exported)
    }
}
