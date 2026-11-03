package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseDetectorTest {
    private val loud = 0.5f
    private val quiet = 0f

    @Test fun aChunkEndsAfterTheSilenceFollowingSpeech() {
        val detector = PauseDetector(minChunkFrames = 0, minSilenceFrames = 3, maxFrames = 100, minSpeechFrames = 1)
        assertFalse(detector.onFrame(loud))
        assertFalse(detector.onFrame(loud))
        assertFalse(detector.onFrame(quiet))
        assertFalse(detector.onFrame(quiet))
        assertTrue(detector.onFrame(quiet))
        assertTrue(detector.reset())
    }

    @Test fun silenceAloneNeverEndsAChunkBeforeTheMaximumAndHasNoSpeech() {
        val detector = PauseDetector(minChunkFrames = 0, minSilenceFrames = 2, maxFrames = 4, minSpeechFrames = 1)
        assertFalse(detector.onFrame(quiet))
        assertFalse(detector.onFrame(quiet))
        assertFalse(detector.onFrame(quiet))
        assertTrue("the maximum length still ends the chunk", detector.onFrame(quiet))
        assertFalse("a chunk with no speech is dropped", detector.reset())
    }

    @Test fun aLongMonologueIsCutAtTheMaximumEvenWithoutAPause() {
        val detector = PauseDetector(minChunkFrames = 0, minSilenceFrames = 20, maxFrames = 3, minSpeechFrames = 1)
        assertFalse(detector.onFrame(loud))
        assertFalse(detector.onFrame(loud))
        assertTrue(detector.onFrame(loud))
        assertTrue(detector.reset())
    }

    @Test fun resetStartsTheNextChunkFromZero() {
        val detector = PauseDetector(minChunkFrames = 0, minSilenceFrames = 2, maxFrames = 100, minSpeechFrames = 1)
        detector.onFrame(loud)
        detector.onFrame(quiet)
        detector.onFrame(quiet)
        detector.reset()
        // A quiet stretch after the reset has no speech in it yet, so it doesn't end a chunk.
        assertFalse(detector.onFrame(quiet))
        assertFalse(detector.onFrame(quiet))
    }

    @Test fun loudnessIsTheRootMeanSquareOfTheSamples() {
        assertEquals(0.5f, PauseDetector.loudness(floatArrayOf(0.5f, -0.5f, 0.5f, -0.5f), 0, 4), 1e-6f)
        assertEquals(0f, PauseDetector.loudness(floatArrayOf(0f, 0f), 0, 2), 0f)
        assertEquals(0f, PauseDetector.loudness(floatArrayOf(0.9f), 0, 0), 0f)
        // Only the requested range counts.
        assertEquals(1f, PauseDetector.loudness(floatArrayOf(0f, 1f, 1f), 1, 3), 1e-6f)
    }

    @Test fun aSingleLoudFrameIsNotSpeech() {
        // One click or knock is not a word: the chunk is not transcribed.
        val detector = PauseDetector(minChunkFrames = 0, minSilenceFrames = 2, maxFrames = 100)
        detector.onFrame(loud)
        detector.onFrame(quiet)
        detector.onFrame(quiet)
        assertFalse(detector.reset())
    }

    @Test fun aChunkNeedsEnoughSpeechFramesToCount() {
        val detector = PauseDetector(minChunkFrames = 0, minSilenceFrames = 2, maxFrames = 100, minSpeechFrames = 6)
        repeat(5) { detector.onFrame(loud) }
        assertFalse(detector.reset())
        repeat(6) { detector.onFrame(loud) }
        assertTrue(detector.reset())
    }

    @Test fun aPauseBeforeTheMinimumLengthDoesNotEndTheChunk() {
        val detector = PauseDetector(
            minSilenceFrames = 2, maxFrames = 100, minSpeechFrames = 1, minChunkFrames = 6, softMaxFrames = 50, softPauseFrames = 1,
        )
        repeat(3) { detector.onFrame(loud) }
        assertFalse("five frames is still short of the minimum", detector.onFrame(quiet))
        assertFalse("the pause is too early to end the chunk", detector.onFrame(quiet))
        assertTrue("once the chunk is long enough, the pause ends it", detector.onFrame(quiet))
    }

    @Test fun afterTheSoftMaximumAShorterPauseEndsTheChunk() {
        val detector = PauseDetector(
            minChunkFrames = 0, minSilenceFrames = 20, maxFrames = 100, minSpeechFrames = 1, softMaxFrames = 4, softPauseFrames = 1,
        )
        assertFalse(detector.onFrame(loud))
        assertFalse(detector.onFrame(loud))
        assertFalse(detector.onFrame(loud))
        assertTrue("a short pause at the soft maximum ends the chunk", detector.onFrame(quiet))
    }

    @Test fun byDefaultAChunkIsAtLeastFiveSecondsBeforeAPauseEndsIt() {
        val detector = PauseDetector()
        var firstEnd = -1
        for (frame in 1..400) {
            val ended = detector.onFrame(if (frame <= 100) loud else quiet)
            if (ended && firstEnd < 0) firstEnd = frame
        }
        assertEquals("the first pause that counts is at five seconds", 166, firstEnd)
    }
}
