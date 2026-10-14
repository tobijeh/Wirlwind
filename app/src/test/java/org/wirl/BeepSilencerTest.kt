package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeepSilencerTest {
    private class FakeMuter(vararg initiallyMuted: Int) : StreamMuter {
        val muted = initiallyMuted.toMutableSet()
        val changes = mutableListOf<Pair<Int, Boolean>>()
        override fun isMuted(stream: Int) = stream in muted
        override fun setMuted(stream: Int, muted: Boolean) {
            changes += stream to muted
            if (muted) this.muted += stream else this.muted -= stream
        }
    }

    @Test fun silenceMutesAudibleStreamsAndRestoreUnmutesThem() {
        val muter = FakeMuter()
        val silencer = BeepSilencer(muter, listOf(1, 5))
        silencer.silence()
        assertEquals(setOf(1, 5), muter.muted)
        silencer.restore()
        assertTrue(muter.muted.isEmpty())
    }

    @Test fun streamsAlreadyMutedByTheUserStayMuted() {
        val muter = FakeMuter(5)
        val silencer = BeepSilencer(muter, listOf(1, 5))
        silencer.silence()
        silencer.restore()
        assertEquals(setOf(5), muter.muted)
        assertFalse(muter.changes.any { it.first == 5 })
    }

    @Test fun repeatedSilenceDoesNotLoseTheRestorePoint() {
        val muter = FakeMuter()
        val silencer = BeepSilencer(muter, listOf(1))
        silencer.silence()
        silencer.silence()
        silencer.restore()
        assertTrue("stream should be audible again", muter.muted.isEmpty())
        assertEquals(listOf(1 to true, 1 to false), muter.changes)
    }

    @Test fun restoreWithoutSilenceDoesNothing() {
        val muter = FakeMuter()
        BeepSilencer(muter, listOf(1)).restore()
        assertTrue(muter.changes.isEmpty())
    }
}
