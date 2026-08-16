package com.unsupportedpastels.hermesandroid.share

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.unsupportedpastels.hermesandroid.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShareReceiverActivityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun manifestExportsOnlyTheNarrowShareReceiverForSendActions() {
        val component = ComponentName(context, ShareReceiverActivity::class.java)
        @Suppress("DEPRECATION")
        val info = context.packageManager.getActivityInfo(component, 0)
        assertTrue(info.exported)

        @Suppress("DEPRECATION")
        val matches = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_SEND).setType("text/plain"),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        assertTrue(matches.any { it.activityInfo.name == ShareReceiverActivity::class.java.name })
    }

    @Test
    fun forwarderAcceptsOnlyBoundedTextAndDistinctContentUris() {
        val incoming = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_TEXT, "Review")
            clipData = ClipData.newUri(context.contentResolver, "items", Uri.parse("content://provider/one")).also {
                it.addItem(ClipData.Item(Uri.parse("file:///private/secret")))
                it.addItem(ClipData.Item(Uri.parse("content://provider/one")))
                it.addItem(ClipData.Item(Uri.parse("content://provider/two")))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val forwarded = requireNotNull(buildShareForwardIntent(context, incoming))

        assertEquals(ComponentName(context, MainActivity::class.java), forwarded.component)
        assertEquals(ACTION_STAGE_SHARE, forwarded.action)
        assertEquals("Review", forwarded.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(2, forwarded.clipData?.itemCount)
        assertTrue(forwarded.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNull(buildShareForwardIntent(context, Intent(Intent.ACTION_VIEW)))
    }
}
