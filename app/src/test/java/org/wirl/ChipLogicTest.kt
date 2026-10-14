package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.wirl.ChipPlacement.Spot

class ChipLogicTest {
    private val screenW = 1080
    private val screenH = 2400
    private val bubble = 168
    private val chipW = 648
    private val chipH = 150
    private val gap = 24

    private fun place(bubbleLeft: Int, bubbleTop: Int, preferLeft: Boolean) = ChipPlacement.place(
        bubbleLeft, bubbleTop, bubble, chipW, chipH, screenW, screenH, gap, preferLeft,
    )

    private fun overlapsBubble(spot: Spot, left: Int, top: Int): Boolean =
        spot.x < left + bubble && left < spot.x + chipW && spot.y < top + bubble && top < spot.y + chipH

    @Test fun chipGoesToTheRoomierSide() {
        // Bubble on the right edge: the chip goes on its left.
        val spot = place(bubbleLeft = 900, bubbleTop = 1000, preferLeft = true)
        assertEquals(900 - gap - chipW, spot.x)
    }

    @Test fun bubbleOnTheLeftEdgePutsTheChipOnTheRight() {
        val spot = place(bubbleLeft = 10, bubbleTop = 1000, preferLeft = false)
        assertEquals(10 + bubble + gap, spot.x)
    }

    @Test fun chipIsNeverOverTheBubbleAcrossTheScreen() {
        for (left in 0..(screenW - bubble) step 60) {
            for (top in 0..(screenH - bubble) step 60) {
                val spot = place(left, top, preferLeft = left + bubble / 2 > screenW / 2)
                assertFalse("bubble at ($left,$top) covered by chip at $spot", overlapsBubble(spot, left, top))
            }
        }
    }

    @Test fun chipStaysOnScreen() {
        for (left in 0..(screenW - bubble) step 90) {
            for (top in 0..(screenH - bubble) step 90) {
                val spot = place(left, top, preferLeft = left > screenW / 2)
                assertTrue(spot.x >= 0 && spot.x + chipW <= screenW)
                assertTrue(spot.y >= 0 && spot.y + chipH <= screenH)
            }
        }
    }

    @Test fun pausesAutoScrollForThreeSecondsAfterATouch() {
        val policy = AutoScrollPolicy()
        policy.touched(now = 1_000)
        assertFalse(policy.allowsAutoScroll(now = 1_500))
        assertFalse(policy.allowsAutoScroll(now = 3_999))
        assertTrue(policy.allowsAutoScroll(now = 4_000))
    }

    @Test fun eachTouchExtendsThePause() {
        val policy = AutoScrollPolicy()
        policy.touched(now = 1_000)
        policy.touched(now = 3_000)
        assertFalse(policy.allowsAutoScroll(now = 5_000))
        assertTrue(policy.allowsAutoScroll(now = 6_000))
    }

    @Test fun autoScrollIsOnBeforeAnyTouch() {
        assertTrue(AutoScrollPolicy().allowsAutoScroll(now = 0))
    }
}
