package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechGateTest {
    @Test fun silenceAndNoiseOnlyRecordingInsertsNothingAndCallsNoClaude() {
        for (silence in listOf("", "   ", "[BLANK_AUDIO]", "(silence) *sighs*", "Thanks for watching!")) {
            val spoken = SpeechGate.spokenWords(silence)
            assertFalse("no Claude call for: $silence", SpeechGate.callsClaude(spoken))
        }
    }

    @Test fun realSpeechCallsClaude() {
        assertTrue(SpeechGate.callsClaude(SpeechGate.spokenWords("[beep] see you at 6")))
    }

    @Test fun anEmptyReplyInsertsNothing() {
        assertNull("an empty reply inserts nothing", SpeechGate.textToInsert("hello", aiEnabled = true, cleaned = Result.success("")))
        assertNull(SpeechGate.textToInsert("hello", aiEnabled = true, cleaned = Result.success("   ")))
    }

    @Test fun aCleanedReplyIsInserted() {
        assertEquals("See you at 6.", SpeechGate.textToInsert("see you at 6", aiEnabled = true, cleaned = Result.success("See you at 6.")))
    }

    @Test fun aFailedCleanupOrCleanupOffInsertsTheSpokenWords() {
        assertEquals("see you at 6", SpeechGate.textToInsert("see you at 6", aiEnabled = true, cleaned = Result.failure(RuntimeException())))
        assertEquals("see you at 6", SpeechGate.textToInsert("see you at 6", aiEnabled = false, cleaned = null))
    }
}
