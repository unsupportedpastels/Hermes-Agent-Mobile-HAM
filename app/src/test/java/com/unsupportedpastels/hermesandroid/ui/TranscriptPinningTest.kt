package com.unsupportedpastels.hermesandroid.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptPinningTest {
    @Test
    fun pinnedWhenListHasNotLaidOutYet() {
        assertTrue(isTranscriptPinnedToBottom(lastVisibleItemIndex = null, totalItemsCount = 0))
        assertTrue(isTranscriptPinnedToBottom(lastVisibleItemIndex = null, totalItemsCount = 12))
    }

    @Test
    fun pinnedWhenNewestItemIsVisible() {
        assertTrue(isTranscriptPinnedToBottom(lastVisibleItemIndex = 11, totalItemsCount = 12))
    }

    @Test
    fun pinnedWhenListIsEmpty() {
        assertTrue(isTranscriptPinnedToBottom(lastVisibleItemIndex = 0, totalItemsCount = 0))
    }

    @Test
    fun notPinnedAfterScrollingUpPastNewestItem() {
        assertFalse(isTranscriptPinnedToBottom(lastVisibleItemIndex = 10, totalItemsCount = 12))
        assertFalse(isTranscriptPinnedToBottom(lastVisibleItemIndex = 0, totalItemsCount = 2))
    }
}
