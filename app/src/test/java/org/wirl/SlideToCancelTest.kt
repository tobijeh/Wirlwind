package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideToCancelTest {
    // A knob resting at x = 500 with a 200 px track to its left.
    private val rest = 500
    private val travel = 200

    @Test fun thePositionIsLockedWhileRecordingAndFreeAfterwards() {
        assertFalse("no dragging while recording", SlideToCancel.repositionAllowed(recording = true))
        assertTrue("dragging works again after recording", SlideToCancel.repositionAllowed(recording = false))
    }

    @Test fun theTrackFlipsToTheRightNearTheLeftEdge() {
        assertEquals(SlideDirection.RIGHT, SlideToCancel.directionFor(restX = 60, reachPx = 200))
        assertEquals(SlideDirection.LEFT, SlideToCancel.directionFor(restX = 500, reachPx = 200))
    }

    @Test fun theKnobStaysOnItsTrack() {
        assertEquals(500, SlideToCancel.clampX(900, rest, travel, SlideDirection.LEFT))
        assertEquals(300, SlideToCancel.clampX(100, rest, travel, SlideDirection.LEFT))
        assertEquals(100, SlideToCancel.clampX(0, 100, travel, SlideDirection.RIGHT))
        assertEquals(300, SlideToCancel.clampX(400, 100, travel, SlideDirection.RIGHT))
    }

    @Test fun progressRunsFromRestToTheFarEnd() {
        assertEquals(0f, SlideToCancel.progress(500, rest, travel, SlideDirection.LEFT), 0.001f)
        assertEquals(0.5f, SlideToCancel.progress(400, rest, travel, SlideDirection.LEFT), 0.001f)
        assertEquals(0f, SlideToCancel.progress(100, 100, travel, SlideDirection.RIGHT), 0.001f)
        assertEquals(1f, SlideToCancel.progress(300, 100, travel, SlideDirection.RIGHT), 0.001f)
    }

    @Test fun pastSixtyPercentTheTrackArmsAndReleaseCancels() {
        assertFalse(SlideToCancel.armed(0.59f))
        assertTrue(SlideToCancel.armed(0.6f))
        assertTrue("release past the threshold cancels", SlideToCancel.cancelsOnRelease(0.6f, velocityTowardFarEnd = 0f))
    }

    @Test fun releaseBeforeTheThresholdDoesNotCancel() {
        assertFalse("a slow release springs back and keeps recording", SlideToCancel.cancelsOnRelease(0.3f, velocityTowardFarEnd = 0f))
    }

    @Test fun aFastFlickCancelsEvenBelowTheThreshold() {
        assertTrue(SlideToCancel.cancelsOnRelease(0.2f, velocityTowardFarEnd = 1_600f))
    }

    @Test fun aFlickThatBarelyMovedDoesNotCancel() {
        assertFalse("a jitter is not a flick", SlideToCancel.cancelsOnRelease(0.02f, velocityTowardFarEnd = 1_600f))
    }

    @Test fun aSlowMoveTowardTheFarEndDoesNotCancel() {
        assertFalse(SlideToCancel.cancelsOnRelease(0.2f, velocityTowardFarEnd = 100f))
    }

    @Test fun tapStillStopsAndProcessesWhileRecording() {
        val gestures = GestureMachine()
        assertEquals(GestureAction.StartRecording, gestures.onTap())
        assertEquals("a tap while recording stops and processes", GestureAction.StopRecording, gestures.onTap())
        assertEquals(GestureMachine.Mode.PROCESSING, gestures.mode)
    }
}
