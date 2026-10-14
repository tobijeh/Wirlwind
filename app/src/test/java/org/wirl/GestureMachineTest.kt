package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Test

class GestureMachineTest {
    private val sela = FieldKey("com.whatsapp", "entry", 10, 20)
    private val other = FieldKey("com.whatsapp", "entry", 10, 300)
    private val original = "um so i think like this is good"
    private val cleaned = "So I think this is good."

    private fun GestureMachine.cleanOnce(field: FieldKey = sela, text: String = original): GestureAction {
        val action = onHold(field, text)
        if (action == GestureAction.Clean) {
            onCleaned(field, text, cleaned)
            finish()
        }
        return action
    }

    @Test fun tapStartsThenStopsDictation() {
        val machine = GestureMachine()
        assertEquals(GestureAction.StartRecording, machine.onTap())
        assertEquals(GestureMachine.Mode.RECORDING, machine.mode)
        assertEquals(GestureAction.StopRecording, machine.onTap())
        assertEquals(GestureMachine.Mode.PROCESSING, machine.mode)
    }

    @Test fun tapWhileProcessingIsIgnored() {
        val machine = GestureMachine()
        machine.onTap()
        machine.onTap()
        assertEquals(GestureAction.Ignore, machine.onTap())
    }

    @Test fun holdCleansTheField() {
        val machine = GestureMachine()
        assertEquals(GestureAction.Clean, machine.onHold(sela, original))
        assertEquals(GestureMachine.Mode.PROCESSING, machine.mode)
    }

    @Test fun holdWithNoFieldDoesNothing() {
        assertEquals(GestureAction.Ignore, GestureMachine().onHold(null, original))
    }

    @Test fun holdWhileRecordingDoesNothing() {
        val machine = GestureMachine()
        machine.onTap()
        assertEquals(GestureAction.Ignore, machine.onHold(sela, original))
        assertEquals(GestureMachine.Mode.RECORDING, machine.mode)
    }

    @Test fun holdAfterCleanupWithUnchangedTextRestoresOriginal() {
        val machine = GestureMachine()
        machine.cleanOnce()
        assertEquals(GestureAction.Undo(original), machine.onHold(sela, cleaned))
    }

    @Test fun holdAfterEditingCleansAgain() {
        val machine = GestureMachine()
        machine.cleanOnce()
        assertEquals(GestureAction.Clean, machine.onHold(sela, cleaned + " Thanks"))
    }

    @Test fun holdAfterUndoCleansAgain() {
        val machine = GestureMachine()
        machine.cleanOnce()
        assertEquals(GestureAction.Undo(original), machine.onHold(sela, cleaned))
        machine.finish()
        // The field now holds the original again, and there's no cleanup left to undo.
        assertEquals(GestureAction.Clean, machine.onHold(sela, original))
    }

    @Test fun holdToggleContinuesUntilAnEdit() {
        val machine = GestureMachine()
        assertEquals(GestureAction.Clean, machine.onHold(sela, original))
        machine.onCleaned(sela, original, cleaned)
        machine.finish()
        assertEquals(GestureAction.Undo(original), machine.onHold(sela, cleaned))
        machine.finish()
        assertEquals(GestureAction.Clean, machine.onHold(sela, original))
        machine.onCleaned(sela, original, cleaned)
        machine.finish()
        assertEquals(GestureAction.Undo(original), machine.onHold(sela, cleaned))
    }

    @Test fun undoIsTiedToTheSpecificField() {
        val machine = GestureMachine()
        machine.cleanOnce(field = sela)
        assertEquals(GestureAction.Clean, machine.onHold(other, cleaned))
    }

    @Test fun switchingFieldForgetsTheCleanup() {
        val machine = GestureMachine()
        machine.cleanOnce(field = sela)
        machine.onHold(other, cleaned)
        machine.finish()
        // Coming back to the first field doesn't undo anything: the record was dropped when we left it.
        assertEquals(GestureAction.Clean, machine.onHold(sela, cleaned))
    }

    @Test fun failedCleanupLeavesNothingToUndo() {
        val machine = GestureMachine()
        assertEquals(GestureAction.Clean, machine.onHold(sela, original))
        machine.finish() // no onCleaned call: the cleanup failed
        assertEquals(GestureAction.Clean, machine.onHold(sela, original))
    }
}
