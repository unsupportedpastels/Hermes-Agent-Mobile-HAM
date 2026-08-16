package com.unsupportedpastels.hermesandroid.share

import android.app.Activity
import android.os.Bundle

/** Narrow exported entry point for Android's Sharesheet; it never renders or sends content. */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildShareForwardIntent(this, intent)?.let(::startActivity)
        finish()
    }
}
