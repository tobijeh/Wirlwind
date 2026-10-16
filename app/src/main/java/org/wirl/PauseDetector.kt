package org.wirl

import kotlin.math.sqrt

/**
 * Finds where a chunk can end. Audio is read in frames of [SAMPLES_PER_FRAME] samples (30 ms at 16 kHz). A frame counts
 * as speech when its loudness is at least [threshold]. A chunk ends after [minSilenceFrames] of quiet that follows
 * speech, or after [maxFrames] whatever is happening, so one long monologue still gets transcribed as it goes.
 */
class PauseDetector(
    private val threshold: Float = DEFAULT_THRESHOLD,
    private val minSilenceFrames: Int = 20,
    private val maxFrames: Int = 20_000 / FRAME_MS,
    /** Frames of speech a chunk needs before it counts as speech. One loud frame is a click, not a word. */
    private val minSpeechFrames: Int = 6,
) {
    private var frames = 0
    private var speechFrames = 0
    private var silentRun = 0

    /** Feeds one frame's loudness. Returns true when the chunk should end after this frame. */
    fun onFrame(loudness: Float): Boolean {
        frames++
        if (loudness >= threshold) {
            speechFrames++
            silentRun = 0
        } else {
            silentRun++
        }
        val pauseAfterSpeech = hasSpeech() && silentRun >= minSilenceFrames
        return pauseAfterSpeech || frames >= maxFrames
    }

    private fun hasSpeech(): Boolean = speechFrames >= minSpeechFrames

    /** Starts the next chunk. Returns whether the chunk just ended had any speech in it. */
    fun reset(): Boolean {
        val hadSpeech = hasSpeech()
        frames = 0
        speechFrames = 0
        silentRun = 0
        return hadSpeech
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 30
        const val SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MS / 1000
        const val DEFAULT_THRESHOLD = 0.01f

        /** Root-mean-square loudness of samples in -1..1, from index [from] up to (not including) [to]. */
        fun loudness(samples: FloatArray, from: Int, to: Int): Float {
            if (to <= from) return 0f
            var sum = 0.0
            for (i in from until to) sum += samples[i].toDouble() * samples[i]
            return sqrt(sum / (to - from)).toFloat()
        }
    }
}
