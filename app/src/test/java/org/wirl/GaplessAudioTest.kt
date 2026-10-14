package org.wirl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GaplessAudioTest {
    /** One 16-bit sample whose value is [n], so the bytes show which sample they are. */
    private fun sample(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte())

    private fun samples(vararg values: Int) = values.fold(byteArrayOf()) { acc, n -> acc + sample(n) }

    @Test fun sliceReturnsTheAudioFromThatSampleToTheEnd() {
        val ring = PcmRing(capacitySamples = 8)
        ring.append(samples(1, 2, 3, 4))
        assertEquals(4L, ring.end)
        assertArrayEquals(samples(2, 3, 4), ring.sliceFrom(1))
        assertArrayEquals(byteArrayOf(), ring.sliceFrom(4))
    }

    @Test fun oldAudioLeavesTheRollingWindow() {
        // Room for 4 samples: after 6 samples, samples 0 and 1 are gone.
        val ring = PcmRing(capacitySamples = 4)
        ring.append(samples(0, 1, 2, 3))
        ring.append(samples(4, 5))
        assertEquals(2L, ring.oldest)
        assertNull("sample 1 has left the window", ring.sliceFrom(1))
        assertArrayEquals(samples(2, 3, 4, 5), ring.sliceFrom(2))
    }

    @Test fun appendWrapsAroundTheBufferWithoutMixingSamples() {
        val ring = PcmRing(capacitySamples = 3)
        for (n in 0 until 7) ring.append(samples(n))
        assertArrayEquals(samples(4, 5, 6), ring.sliceFrom(4))
    }

    @Test fun replayStartsLookbackBeforeTheEndOfThePreviousSession() {
        // A session ended at 2 s of audio. The buffer still holds everything, so the replay starts 1.5 s earlier.
        val plan = ReplayPlan.plan(endedAt = 32_000, oldest = 0)
        assertEquals(8_000L, plan.start)
        assertEquals(0L, plan.gapSamples)
        assertEquals(0L, plan.gapMs)
    }

    @Test fun replayReportsAGapWhenTheWantedAudioHasLeftTheBuffer() {
        // The session ended at 30 000 samples, but the buffer only holds from 20 000. Lookback wants 6 000.
        val plan = ReplayPlan.plan(endedAt = 30_000, oldest = 20_000)
        assertEquals(20_000L, plan.start)
        assertEquals(14_000L, plan.gapSamples)
        assertEquals(875L, plan.gapMs)
    }
}
