package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SegmentedDictationTest {
    /** Any recognizer error that isn't "no speech". */
    private val someError = 5

    private fun segmented() = DictationSession(segmented = true).also { it.onListening() }

    @Test fun segmentsAssembleInOrderAndTheSessionFinishesAtTheEnd() {
        val session = segmented()
        session.onEvent(RecognizerEvent.Segment("one"))
        session.onEvent(RecognizerEvent.Segment("two"))
        session.onEvent(RecognizerEvent.Segment("three"))
        // The session stays open until the user stops, so stopping waits for the end of the session.
        assertNull(session.stopByUser())
        assertEquals(
            SessionAction.Finish("one two three", null),
            session.onEvent(RecognizerEvent.EndOfSegments),
        )
        assertEquals("mode: segmented, segments: 3, restarts: 0, audio gaps: none", session.summary())
    }

    @Test fun aSegmentRepeatedBackToBackIsNotDuplicated() {
        val session = segmented()
        session.onEvent(RecognizerEvent.Segment("one"))
        session.onEvent(RecognizerEvent.Segment("two"))
        session.onEvent(RecognizerEvent.Segment("two"))
        session.onEvent(RecognizerEvent.Segment("three"))
        assertEquals("one two three", session.transcript)
        assertEquals("mode: segmented, segments: 3, restarts: 0, audio gaps: none", session.summary())
    }

    @Test fun repeatedWordsThatAreNotBackToBackAreKept() {
        val session = segmented()
        session.onEvent(RecognizerEvent.Segment("yes"))
        session.onEvent(RecognizerEvent.Segment("no"))
        session.onEvent(RecognizerEvent.Segment("yes"))
        assertEquals("yes no yes", session.transcript)
    }

    @Test fun partialSpeechIsKeptWhenTheSessionEnds() {
        val session = segmented()
        session.onEvent(RecognizerEvent.Segment("one"))
        session.onEvent(RecognizerEvent.Partial("two"))
        assertEquals(SessionAction.Finish("one two", null), session.onEvent(RecognizerEvent.EndOfSegments))
    }

    @Test fun resultsAfterSegmentsDoNotRepeatTheText() {
        val session = segmented()
        session.onEvent(RecognizerEvent.Segment("one"))
        assertNull(session.onEvent(RecognizerEvent.Results("one")))
        assertEquals("one", session.transcript)
    }

    @Test fun errorAfterSegmentsFinishesWithTheTextHeard() {
        val session = segmented()
        session.onEvent(RecognizerEvent.Segment("one"))
        assertEquals(
            SessionAction.Finish("one", DictationError.from(someError), someError),
            session.onEvent(RecognizerEvent.Error(someError)),
        )
    }

    @Test fun segmentedSessionThatFailsBeforeAnyTextFallsBackToRestarts() {
        val session = segmented()
        assertEquals(SessionAction.Restart, session.onEvent(RecognizerEvent.Error(someError)))
        assertEquals(DictationSession.Mode.RESTART_MIC, session.mode)
        session.onListening()
        assertEquals(SessionAction.Restart, session.onEvent(RecognizerEvent.Results("hello there")))
        session.onListening()
        assertNull(session.stopByUser())
        assertEquals(
            SessionAction.Finish("hello there how are you", null),
            session.onEvent(RecognizerEvent.Results("how are you")),
        )
    }

    @Test fun fallbackSummaryNamesTheFallbackAndCountsRestarts() {
        val session = segmented()
        session.onEvent(RecognizerEvent.Error(someError))
        session.onListening()
        session.onEvent(RecognizerEvent.Results("hello"))
        assertEquals(
            "mode: restart with mic (segmented unsupported, fell back), segments: 1, restarts: 1, audio gaps: none",
            session.summary(),
        )
    }

    @Test fun segmentedRecognizerThatSendsOnlyResultsFallsBackAndKeepsTheText() {
        val session = segmented()
        assertEquals(SessionAction.Restart, session.onEvent(RecognizerEvent.Results("hello")))
        assertEquals(DictationSession.Mode.RESTART_MIC, session.mode)
        assertEquals("hello", session.transcript)
    }

    @Test fun restartModeStillRestartsOnEachPauseAndCountsThem() {
        val session = DictationSession().also { it.onListening() }
        assertEquals(SessionAction.Restart, session.onEvent(RecognizerEvent.Results("hello there")))
        session.onListening()
        assertEquals(SessionAction.Restart, session.onEvent(RecognizerEvent.Results("how are you")))
        assertEquals("mode: restart with mic, segments: 2, restarts: 2, audio gaps: none", session.summary())
    }

    @Test fun fallbackPathKeepsTheTextAcrossRestartsAndStops() {
        val session = segmented()
        session.onEvent(RecognizerEvent.Error(someError))
        session.onListening()
        session.onEvent(RecognizerEvent.Results("hello there"))
        session.onListening()
        assertNull(session.stopByUser())
        assertEquals(
            SessionAction.Finish("hello there today", null),
            session.onEvent(RecognizerEvent.Results("today")),
        )
    }
}
