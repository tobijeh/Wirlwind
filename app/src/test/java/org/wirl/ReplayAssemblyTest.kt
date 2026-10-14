package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayAssemblyTest {
    private fun ownAudio() = DictationSession(segmented = false, ownAudio = true).also { it.onListening() }

    @Test fun aWordSpokenDuringARestartIsKeptOnceAndInOrder() {
        // The first session heard "I want to". The second replays a little before the gap, so "to" comes twice and is
        // kept once. "go" was spoken during the gap and only the replay gives it to the second session.
        val session = ownAudio()
        assertEquals(SessionAction.Restart, session.onEvent(RecognizerEvent.Results("I want to")))
        session.onListening()
        session.beginReplay()
        assertEquals(SessionAction.Restart, session.onEvent(RecognizerEvent.Results("to go home")))
        // Stopping in the gap between sessions finishes straight away.
        assertEquals(SessionAction.Finish("I want to go home", null), session.stopByUser())
    }

    @Test fun replayedOverlapOfSeveralWordsIsRemoved() {
        val session = ownAudio()
        session.onEvent(RecognizerEvent.Results("hello there"))
        session.onListening()
        session.beginReplay()
        session.onEvent(RecognizerEvent.Results("there how are you"))
        assertEquals(SessionAction.Finish("hello there how are you", null), session.stopByUser())
    }

    @Test fun repeatedWordsThatAreNotAReplayAreKept() {
        // Two real "no"s in a row are not a replay overlap. Only a replayed session drops repeated words.
        val session = ownAudio()
        session.onEvent(RecognizerEvent.Results("no"))
        session.onListening()
        session.onEvent(RecognizerEvent.Results("no"))
        assertEquals(SessionAction.Finish("no no", null), session.stopByUser())
    }

    @Test fun livePreviewUpdatesWhileSpeaking() {
        val session = ownAudio()
        session.onEvent(RecognizerEvent.Partial("hel"))
        assertEquals("hel", session.transcript)
        session.onEvent(RecognizerEvent.Partial("hello wor"))
        assertEquals("hello wor", session.transcript)
        session.onEvent(RecognizerEvent.Partial("hello world"))
        assertEquals("hello world", session.transcript)
    }

    @Test fun gapsAndMicFallbackAppearInTheSummary() {
        val session = ownAudio()
        session.noteGap(875)
        session.useMicrophone("own audio unavailable, using the mic")
        assertEquals(
            "mode: restart with mic (own audio unavailable, using the mic), segments: 0, restarts: 0, audio gaps: 1 (longest 875 ms)",
            session.summary(),
        )
    }

    @Test fun segmentedFailureWithOwnAudioAllowedFallsBackToOwnAudio() {
        val session = DictationSession(segmented = true, ownAudio = true).also { it.onListening() }
        assertEquals(SessionAction.Restart, session.onEvent(RecognizerEvent.Error(5)))
        assertEquals(DictationSession.Mode.RESTART_OWN_AUDIO, session.mode)
        assertTrue(session.summary().startsWith("mode: restart with own audio (segmented unsupported, fell back)"))
    }
}
