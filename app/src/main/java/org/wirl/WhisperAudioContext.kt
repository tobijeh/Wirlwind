package org.wirl

import kotlin.math.ceil

/**
 * How much of Whisper's encoder window a chunk needs. Whisper encodes a full 30-second window whatever the audio length,
 * so a short chunk costs as much as a long one. Cutting the window to the chunk's length makes short chunks faster.
 */
object WhisperAudioContext {
    /** Encoder frames per second of audio. The full 30-second window is 1500 frames. */
    const val FRAMES_PER_SECOND = 50
    const val FULL_WINDOW = 1500

    /** Never shorter than this, so very short chunks keep enough context to decode. */
    const val MIN_WINDOW = 256

    /** Extra frames past the end of the audio, so the last words are never cut off. */
    const val PADDING = 50

    /** The audio context for a chunk of [samples] samples at 16 kHz. */
    fun forSamples(samples: Int): Int {
        val seconds = samples.toDouble() / PauseDetector.SAMPLE_RATE
        val frames = ceil(seconds * FRAMES_PER_SECOND).toInt() + PADDING
        return frames.coerceIn(MIN_WINDOW, FULL_WINDOW)
    }
}
