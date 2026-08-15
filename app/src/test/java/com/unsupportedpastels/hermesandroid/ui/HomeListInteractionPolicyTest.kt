package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeListInteractionPolicyTest {
    @Test
    fun asynchronousContentInsertionPinsUntouchedListBackToTop() {
        val decision = decideHomeListPinning(
            userHasScrolled = false,
            firstVisibleItemIndex = 2,
            firstVisibleItemScrollOffset = 0,
        )

        assertFalse(decision.userHasScrolled)
        assertTrue(decision.pinToTop)
    }

    @Test
    fun nonDragUserScrollIsRecognized() {
        assertTrue(
            isUserInitiatedHomeScroll(
                available = Offset(0f, -120f),
                source = NestedScrollSource.UserInput,
            ),
        )
    }

    @Test
    fun programmaticScrollDoesNotDisableInitialPinning() {
        assertFalse(
            isUserInitiatedHomeScroll(
                available = Offset(0f, -120f),
                source = NestedScrollSource.SideEffect,
            ),
        )

        val decision = decideHomeListPinning(
            userHasScrolled = false,
            firstVisibleItemIndex = 2,
            firstVisibleItemScrollOffset = 12,
        )

        assertFalse(decision.userHasScrolled)
        assertTrue(decision.pinToTop)
    }

    @Test
    fun swipeDeleteRemainsEligibleDuringPostReleaseSettlement() {
        assertTrue(
            shouldRequestSwipeDelete(
                pointerPressed = false,
                gestureSettlingToDelete = true,
            ),
        )
    }

    @Test
    fun anchorChangesWithoutAGestureDoNotRequestDeletion() {
        assertFalse(
            shouldRequestSwipeDelete(
                pointerPressed = false,
                gestureSettlingToDelete = false,
            ),
        )
    }
}