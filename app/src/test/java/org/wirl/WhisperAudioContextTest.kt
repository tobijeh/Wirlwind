package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperAudioContextTest {
    private val rate = PauseDetector.SAMPLE_RATE

    @Test fun anEmptyOrVeryShortChunkGetsTheMinimumWindow() {
        assertEquals(WhisperAudioContext.MIN_WINDOW, WhisperAudioContext.forSamples(0))
        assertEquals(WhisperAudioContext.MIN_WINDOW, WhisperAudioContext.forSamples(rate))
    }

    @Test fun theWindowFollowsTheChunkLength() {
        // Ten seconds is 500 frames, plus the padding past the end of the audio.
        assertEquals(550, WhisperAudioContext.forSamples(10 * rate))
    }

    @Test fun aLongChunkNeverGetsMoreThanTheFullWindow() {
        assertEquals(WhisperAudioContext.FULL_WINDOW, WhisperAudioContext.forSamples(30 * rate))
        assertEquals(WhisperAudioContext.FULL_WINDOW, WhisperAudioContext.forSamples(60 * rate))
    }

    @Test fun alongerChunkNeverGetsASmallerWindow() {
        var previous = 0
        for (seconds in 0..30) {
            val window = WhisperAudioContext.forSamples(seconds * rate)
            assertTrue("window for $seconds s", window >= previous)
            previous = window
        }
    }
}
