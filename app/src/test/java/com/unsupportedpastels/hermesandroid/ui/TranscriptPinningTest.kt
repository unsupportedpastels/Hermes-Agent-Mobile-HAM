package com.unsupportedpastels.hermesandroid.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptPinningTest {
    @Test
    fun notAtEndWhenListHasNotLaidOutYet() {
        assertFalse(
            isTranscriptAtTrueEnd(
                lastVisibleItemIndex = null,
                totalItemsCount = 12,
                lastVisibleItemBottom = 0,
                viewportEnd = 800,
            ),
        )
    }

    @Test
    fun notAtEndWhenListIsEmpty() {
        assertFalse(
            isTranscriptAtTrueEnd(
                lastVisibleItemIndex = null,
                totalItemsCount = 0,
                lastVisibleItemBottom = 0,
                viewportEnd = 800,
            ),
        )
    }

    @Test
    fun atEndWhenNewestItemBottomIsFlushWithViewportEnd() {
        assertTrue(
            isTranscriptAtTrueEnd(
                lastVisibleItemIndex = 11,
                totalItemsCount = 12,
                lastVisibleItemBottom = 800,
                viewportEnd = 800,
            ),
        )
    }

    @Test
    fun notAtEndWhenTallStreamingTailIsBelowTheFold() {
        // A final message taller than the viewport keeps the last index visible
        // while its tail is scrolled below the fold; the transcript is only at
        // its true end once that tail is flush with the viewport bottom.
        assertFalse(
            isTranscriptAtTrueEnd(
                lastVisibleItemIndex = 11,
                totalItemsCount = 12,
                lastVisibleItemBottom = 1100,
                viewportEnd = 800,
            ),
        )
    }

    @Test
    fun atEndWhenContentIsShorterThanViewport() {
        assertTrue(
            isTranscriptAtTrueEnd(
                lastVisibleItemIndex = 0,
                totalItemsCount = 1,
                lastVisibleItemBottom = 400,
                viewportEnd = 800,
            ),
        )
    }

    @Test
    fun notAtEndAfterScrollingUpPastNewestItem() {
        assertFalse(
            isTranscriptAtTrueEnd(
                lastVisibleItemIndex = 10,
                totalItemsCount = 12,
                lastVisibleItemBottom = 700,
                viewportEnd = 800,
            ),
        )
        assertFalse(
            isTranscriptAtTrueEnd(
                lastVisibleItemIndex = 0,
                totalItemsCount = 2,
                lastVisibleItemBottom = 400,
                viewportEnd = 800,
            ),
        )
    }

    @Test
    fun toleranceAbsorbsRoundingAtTheClampedEnd() {
        assertTrue(
            isTranscriptAtTrueEnd(
                lastVisibleItemIndex = 11,
                totalItemsCount = 12,
                lastVisibleItemBottom = 802,
                viewportEnd = 800,
                tolerance = 4,
            ),
        )
    }
}
