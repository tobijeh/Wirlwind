package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRulesTest {
    @Test fun aRecordingUnderOneSecondIsTooShort() {
        assertTrue(RecordingRules.tooShort(0))
        assertTrue(RecordingRules.tooShort(999))
        assertFalse("exactly one second is long enough", RecordingRules.tooShort(1_000))
    }

    @Test fun clickingWithNoWordsDoesNotCallClaude() {
        // Noise, beeps, sound tags, and Whisper's invented phrases all reduce to nothing.
        for (noise in listOf("", "  ", "[BLANK_AUDIO]", "(beep)", "*clicks*", "Thank you.", "uh", "a.", "I")) {
            val spoken = SpeechGate.spokenWords(noise)
            assertFalse("no Claude call for: '$noise'", SpeechGate.callsClaude(spoken))
        }
    }

    @Test fun aTwoLetterWordIsNotSentToClaude() {
        assertFalse(SpeechGate.callsClaude("ok"))
        assertTrue(SpeechGate.callsClaude("yes"))
    }

    @Test fun remarksAboutTheRecordingAreRecognised() {
        for (remark in listOf(
            "No speech detected.",
            "No speech.",
            "Silence.",
            "Unclear audio, please try again.",
            "The audio is unclear.",
            "I couldn't hear anything.",
            "Please try recording again.",
            "Nothing was said.",
        )) {
            assertTrue("remark: $remark", SpeechGate.isRecordingRemark(remark))
        }
    }

    @Test fun realSentencesThatMentionSilenceAreKept() {
        for (sentence in listOf(
            "Silence is golden, as they say.",
            "Silent night, holy night.",
            "Let's try again tomorrow.",
            "No speech from the boss, just memos.",
            "Thanks, I couldn't hear you on the call so I'm following up by email.",
        )) {
            assertFalse("kept: $sentence", SpeechGate.isRecordingRemark(sentence))
        }
    }

    @Test fun aRemarkReplyInsertsNothing() {
        assertNull(SpeechGate.textToInsert("no speech", aiEnabled = true, cleaned = Result.success("No speech detected.")))
        assertNull(SpeechGate.textToInsert("hmm", aiEnabled = true, cleaned = Result.success("Unclear audio, please try again.")))
    }

    @Test fun aRealReplyIsStillInserted() {
        assertEquals(
            "Silence is golden.",
            SpeechGate.textToInsert("silence is golden", aiEnabled = true, cleaned = Result.success("Silence is golden.")),
        )
    }

    @Test fun tileNeedsSetupWhenAccessibilityOrKeyIsMissing() {
        assertEquals(TileState.NEEDS_SETUP, WirlTileLogic.stateFor(accessibilityOn = false, apiKeySaved = true, bubbleEnabled = true))
        assertEquals(TileState.NEEDS_SETUP, WirlTileLogic.stateFor(accessibilityOn = true, apiKeySaved = false, bubbleEnabled = false))
    }

    @Test fun tileShowsOnOrOffFromTheBubbleSetting() {
        assertEquals(TileState.ON, WirlTileLogic.stateFor(accessibilityOn = true, apiKeySaved = true, bubbleEnabled = true))
        assertEquals(TileState.OFF, WirlTileLogic.stateFor(accessibilityOn = true, apiKeySaved = true, bubbleEnabled = false))
    }

    @Test fun tapTogglesOnAndOffButNeedsSetupToggleNothing() {
        assertEquals(false, WirlTileLogic.afterTap(TileState.ON, bubbleEnabled = true))
        assertEquals(true, WirlTileLogic.afterTap(TileState.OFF, bubbleEnabled = false))
        assertNull(WirlTileLogic.afterTap(TileState.NEEDS_SETUP, bubbleEnabled = true))
    }
}
