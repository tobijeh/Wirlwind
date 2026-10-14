package org.wirl

/** Audio format the recognizer is fed: 16 kHz, mono, 16-bit PCM. */
object AudioFormat16k {
    const val SAMPLE_RATE = 16_000
    const val BYTES_PER_SAMPLE = 2
}

/**
 * A rolling window of the most recent audio, addressed by absolute sample number from the start of capture. Audio older
 * than the window is dropped automatically, so the buffer never grows.
 */
class PcmRing(private val capacitySamples: Int) {
    private val bytes = ByteArray(capacitySamples * AudioFormat16k.BYTES_PER_SAMPLE)

    /** Total samples captured so far. The next sample to arrive is numbered [end]. */
    var end: Long = 0
        private set

    /** The oldest sample still held. */
    val oldest: Long get() = maxOf(0L, end - capacitySamples)

    /** Appends 16-bit PCM bytes. Only the first [length] bytes are used. */
    fun append(chunk: ByteArray, length: Int = chunk.size) {
        val size = bytes.size
        var pos = ((end * AudioFormat16k.BYTES_PER_SAMPLE) % size).toInt()
        for (i in 0 until length) {
            bytes[pos] = chunk[i]
            pos++
            if (pos == size) pos = 0
        }
        end += length / AudioFormat16k.BYTES_PER_SAMPLE
    }

    /** The audio from [fromSample] to the end, or null if that audio is no longer held. */
    fun sliceFrom(fromSample: Long): ByteArray? {
        if (fromSample < oldest || fromSample > end) return null
        val count = ((end - fromSample) * AudioFormat16k.BYTES_PER_SAMPLE).toInt()
        val out = ByteArray(count)
        var pos = ((fromSample * AudioFormat16k.BYTES_PER_SAMPLE) % bytes.size).toInt()
        for (i in 0 until count) {
            out[i] = bytes[pos]
            pos++
            if (pos == bytes.size) pos = 0
        }
        return out
    }
}

/**
 * Where a new recognizer session picks up. A session that ended at [endedAt] is followed by one that replays from
 * [LOOKBACK_MS] before that point, so words spoken across the gap are heard again rather than lost. The overlap is
 * removed from the text afterwards.
 */
object ReplayPlan {
    const val LOOKBACK_MS = 1_500L
    private const val LOOKBACK_SAMPLES = (AudioFormat16k.SAMPLE_RATE * LOOKBACK_MS / 1000)

    /** [start] is the sample to replay from. [gapSamples] is audio that was wanted but has already left the buffer. */
    data class Plan(val start: Long, val gapSamples: Long) {
        val gapMs: Long get() = gapSamples * 1000 / AudioFormat16k.SAMPLE_RATE
    }

    fun plan(endedAt: Long, oldest: Long): Plan {
        val wanted = maxOf(0L, endedAt - LOOKBACK_SAMPLES)
        val start = maxOf(wanted, oldest)
        return Plan(start = start, gapSamples = maxOf(0L, oldest - wanted))
    }
}
