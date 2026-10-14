package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DictationSessionTest {
    private fun started() = DictationSession().also { it.onListening() }

    @Test fun resultsWhileDictatingRestartTheRecognizer() {
        val session = started()
        assertEquals(SessionAction.Restart, session.onEvent(RecognizerEvent.Results("hello there")))
        session.onListening()
        assertEquals(SessionAction.Restart, session.onEvent(RecognizerEvent.Results("how are you")))
    }

    @Test fun transcriptKeepsAppendingAcrossRestarts() {
        val session = started()
        session.onEvent(RecognizerEvent.Results("hello there"))
        session.onListening()
        session.onEvent(RecognizerEvent.Results("how are you"))
        session.onListening()
        val finish = session.stopByUser()
        // Stopping while listening waits for the recognizer's last result.
        assertNull(finish)
        val last = session.onEvent(RecognizerEvent.Results("today"))
        assertEquals(SessionAction.Finish("hello there how are you today", null), last)
    }

    @Test fun stopDuringRestartGapFinishesImmediately() {
        val session = started()
        session.onEvent(RecognizerEvent.Results("hello"))
        // The recognizer hasn't been restarted yet, so there's no result to wait for.
        assertEquals(SessionAction.Finish("hello", null), session.stopByUser())
    }

    @Test fun partialResultIsIncludedWhenStoppedWithoutAFinalResult() {
        val session = started()
        session.onEvent(RecognizerEvent.Partial("meet me at"))
        assertEquals("meet me at", session.transcript)
        session.stopByUser()
        assertEquals(SessionAction.Finish("meet me at", null), session.onEvent(RecognizerEvent.Error(7)))
    }

    @Test fun silenceMidDictationRestarts() {
        val session = started()
        session.onEvent(RecognizerEvent.Results("first part"))
        session.onListening()
        assertEquals(SessionAction.Restart, session.onEvent(RecognizerEvent.Error(7)))
    }

    @Test fun silenceWithNothingSaidFinishesWithNoSpeech() {
        val session = started()
        assertEquals(
            SessionAction.Finish("", DictationError.NO_SPEECH),
            session.onEvent(RecognizerEvent.Error(7)),
        )
    }

    @Test fun errorKeepsWhatWasSaid() {
        val session = started()
        session.onEvent(RecognizerEvent.Results("hello there"))
        session.onListening()
        session.onEvent(RecognizerEvent.Partial("how are"))
        assertEquals(
            SessionAction.Finish("hello there how are", DictationError.NO_INTERNET),
            session.onEvent(RecognizerEvent.Error(2)),
        )
    }

    @Test fun networkErrorAfterStopStillKeepsTheText() {
        val session = started()
        session.onEvent(RecognizerEvent.Partial("call me"))
        session.stopByUser()
        assertEquals(
            SessionAction.Finish("call me", DictationError.NO_INTERNET),
            session.onEvent(RecognizerEvent.Error(3)),
        )
    }
}
