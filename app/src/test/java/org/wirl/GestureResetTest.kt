package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Test

class GestureResetTest {
    private val field = FieldKey("com.example", "message", 0, 0)

    @Test fun resetReturnsToIdleSoTheNextTapStartsARecording() {
        val gestures = GestureMachine()
        assertEquals(GestureAction.StartRecording, gestures.onTap())
        gestures.reset()
        assertEquals(GestureMachine.Mode.IDLE, gestures.mode)
        assertEquals(GestureAction.StartRecording, gestures.onTap())
    }

    @Test fun resetForgetsTheLastCleanupSoHoldDoesNotUndoIt() {
        val gestures = GestureMachine()
        gestures.onCleaned(field, original = "teh cat", cleaned = "The cat.")
        gestures.reset()
        assertEquals(GestureAction.Clean, gestures.onHold(field, "The cat."))
    }
}
