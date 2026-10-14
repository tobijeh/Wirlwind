package org.wirl

import android.media.AudioManager

/** Mutes and unmutes one audio stream. Separate from Android so the rules can be tested. */
interface StreamMuter {
    fun isMuted(stream: Int): Boolean
    fun setMuted(stream: Int, muted: Boolean)
}

/** The real stream, through AudioManager. */
class AndroidStreamMuter(private val audio: AudioManager) : StreamMuter {
    override fun isMuted(stream: Int): Boolean = audio.isStreamMute(stream)

    override fun setMuted(stream: Int, muted: Boolean) {
        audio.adjustStreamVolume(stream, if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
    }
}

/**
 * Silences the recognizer's start and stop beeps during automatic restarts. Only streams that were not already muted
 * are muted, and [restore] puts back only those, so the user's own mute settings are never changed for good.
 */
class BeepSilencer(private val muter: StreamMuter, private val streams: List<Int>) {
    private val muted = mutableListOf<Int>()

    /** Mutes the beep streams that are currently audible. Safe to call again while already silenced. */
    fun silence() {
        for (stream in streams) {
            if (stream in muted || muter.isMuted(stream)) continue
            muter.setMuted(stream, true)
            muted += stream
        }
    }

    /** Unmutes what [silence] muted. Safe to call when nothing was muted. */
    fun restore() {
        for (stream in muted) muter.setMuted(stream, false)
        muted.clear()
    }
}
