package org.wirl

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationSoundTest {
    private class FakeFocus(var grants: Boolean = true) : FocusPort {
        var requests = 0
        var releases = 0
        override fun request(): Boolean {
            requests++
            return grants
        }
        override fun release() {
            releases++
        }
    }

    private class FakeMuter(var music: Boolean = false, vararg muted: Int) : StreamMuter {
        val muted = muted.toMutableSet()
        val changes = mutableListOf<Pair<Int, Boolean>>()
        override fun isMuted(stream: Int) = stream in muted
        override fun setMuted(stream: Int, muted: Boolean) {
            changes += stream to muted
            if (muted) this.muted += stream else this.muted -= stream
        }
        override fun isMusicActive() = music
    }

    private class MemoryStore(var saved: Set<Int> = emptySet()) : MutedStreamStore {
        override fun load() = saved
        override fun save(streams: Set<Int>) {
            saved = streams
        }
    }

    private var clock = 0L
    private val tick: () -> Long = { clock += 10; clock }

    private fun sound(
        enabled: Boolean = true,
        focus: FakeFocus = FakeFocus(),
        muter: FakeMuter = FakeMuter(),
        store: MemoryStore = MemoryStore(),
    ) = DictationSound(enabled, focus, muter, store, tick)

    private val beepStreams = setOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_MUSIC)

    @Test fun normalStopRestoresStreamsAndReleasesFocus() {
        val focus = FakeFocus()
        val muter = FakeMuter()
        val store = MemoryStore()
        val sound = sound(focus = focus, muter = muter, store = store)
        sound.begin()
        sound.silenceRestart()
        sound.restoreRestart()
        sound.silenceRestart()
        sound.end()
        assertTrue("no stream left muted", muter.muted.isEmpty())
        assertEquals(1, focus.releases)
        assertTrue("restore point cleared", store.saved.isEmpty())
    }

    @Test fun errorEndsWithStreamsRestoredAndFocusReleased() {
        val focus = FakeFocus()
        val muter = FakeMuter()
        val sound = sound(focus = focus, muter = muter)
        sound.begin()
        sound.silenceRestart()
        // An error ends the dictation mid-restart.
        sound.end()
        assertTrue(muter.muted.isEmpty())
        assertEquals(1, focus.releases)
    }

    @Test fun cancellationEndsWithStreamsRestoredAndFocusReleased() {
        val focus = FakeFocus()
        val muter = FakeMuter()
        val sound = sound(focus = focus, muter = muter)
        sound.begin()
        sound.silenceRestart()
        sound.end()
        sound.end()
        assertTrue(muter.muted.isEmpty())
        assertEquals("focus is released once", 1, focus.releases)
    }

    @Test fun refusedFocusIsNotReleased() {
        val focus = FakeFocus(grants = false)
        val sound = sound(focus = focus)
        sound.begin()
        sound.end()
        assertEquals(0, focus.releases)
        assertTrue(sound.log.any { it.contains("refused") })
    }

    @Test fun togglingOffMakesNoFocusRequestsAndNoMutes() {
        val focus = FakeFocus()
        val muter = FakeMuter()
        val store = MemoryStore()
        val sound = sound(enabled = false, focus = focus, muter = muter, store = store)
        sound.begin()
        sound.silenceRestart()
        sound.restoreRestart()
        sound.end()
        assertEquals(0, focus.requests)
        assertEquals(0, focus.releases)
        assertTrue("no stream changed", muter.changes.isEmpty())
        assertTrue("nothing stored", store.saved.isEmpty())
        assertTrue(sound.log.isEmpty())
    }

    @Test fun musicThatIsPlayingKeepsItsStream() {
        val muter = FakeMuter(music = true)
        val sound = sound(muter = muter)
        sound.begin()
        sound.silenceRestart()
        assertFalse("music stream left audible", muter.muted.contains(AudioManager.STREAM_MUSIC))
        assertTrue(muter.muted.containsAll(setOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)))
        assertTrue(sound.log.any { it.contains("left music stream alone") })
    }

    @Test fun repeatedSilenceKeepsTheRestorePoint() {
        val muter = FakeMuter()
        val sound = sound(muter = muter)
        sound.silenceRestart()
        sound.silenceRestart()
        sound.restoreRestart()
        assertTrue(muter.muted.isEmpty())
        assertEquals("each stream muted and restored once", beepStreams.size * 2, muter.changes.size)
    }

    @Test fun streamsAlreadyMutedByTheUserStayMuted() {
        val muter = FakeMuter(false, AudioManager.STREAM_SYSTEM)
        val sound = sound(muter = muter)
        sound.silenceRestart()
        sound.end()
        assertTrue(muter.muted.contains(AudioManager.STREAM_SYSTEM))
        assertFalse(muter.changes.any { it.first == AudioManager.STREAM_SYSTEM })
    }

    @Test fun restoresStreamsLeftMutedByAKilledService() {
        val muter = FakeMuter(false, AudioManager.STREAM_NOTIFICATION)
        val store = MemoryStore(setOf(AudioManager.STREAM_NOTIFICATION))
        val lines = DictationSound.restoreLeftovers(muter, store)
        assertTrue(muter.muted.isEmpty())
        assertTrue(store.saved.isEmpty())
        assertEquals(1, lines.size)
    }

    @Test fun logRecordsFocusAndMuteEventsWithTimes() {
        val sound = sound()
        sound.begin()
        sound.silenceRestart()
        sound.restoreRestart()
        sound.end()
        assertEquals("+10 ms: audio focus granted", sound.log.first())
        assertTrue(sound.log.any { it.endsWith("muted notification stream") })
        assertTrue(sound.log.any { it.endsWith("restored notification stream") })
        assertEquals("audio focus released", sound.log.last().substringAfter(": "))
    }
}
