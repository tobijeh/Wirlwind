package org.wirl

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/** Mutes and unmutes audio streams and reads whether music is playing. Separate from Android so the rules can be tested. */
interface StreamMuter {
    fun isMuted(stream: Int): Boolean
    fun setMuted(stream: Int, muted: Boolean)
    fun isMusicActive(): Boolean
}

/** The real streams, through AudioManager. */
class AndroidStreamMuter(private val audio: AudioManager) : StreamMuter {
    override fun isMuted(stream: Int): Boolean = audio.isStreamMute(stream)

    override fun setMuted(stream: Int, muted: Boolean) {
        audio.adjustStreamVolume(stream, if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
    }

    override fun isMusicActive(): Boolean = audio.isMusicActive
}

/** Exclusive audio focus for the dictation, so music and video pause while it runs. */
interface FocusPort {
    /** Returns true if focus was granted. */
    fun request(): Boolean
    fun release()
}

class AndroidAudioFocus(private val audio: AudioManager) : FocusPort {
    private var held: AudioFocusRequest? = null

    override fun request(): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { }
            .build()
        val granted = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        held = request.takeIf { granted }
        return granted
    }

    override fun release() {
        held?.let { audio.abandonAudioFocusRequest(it) }
        held = null
    }
}

/** Remembers which streams we muted, so a stream stays restorable if the service is killed while it's muted. */
interface MutedStreamStore {
    fun load(): Set<Int>
    fun save(streams: Set<Int>)
}

/**
 * Sound for one dictation. Audio focus is held from start to stop. The recognizer's beep plays on a stream, so during
 * an automatic restart the beep streams are muted for a moment and restored straight after. Music that is still
 * playing keeps its stream. The start and stop sounds are left alone. Every change is logged for Diagnostics.
 */
class DictationSound(
    private val enabled: Boolean,
    private val focus: FocusPort,
    private val muter: StreamMuter,
    private val store: MutedStreamStore,
    private val now: () -> Long,
) {
    private val events = mutableListOf<String>()
    private val muted = mutableSetOf<Int>()
    private var startedAt = now()
    private var focusHeld = false

    /** What happened, in order, with times from the start of the dictation. */
    val log: List<String> get() = events.toList()

    /** Requests audio focus, so media pauses. */
    fun begin() {
        startedAt = now()
        if (!enabled) return
        focusHeld = focus.request()
        record(if (focusHeld) "audio focus granted" else "audio focus refused")
    }

    /** Mutes the beep streams for an automatic restart. Streams already muted, and music that is playing, are skipped. */
    fun silenceRestart() {
        if (!enabled) return
        for (stream in BEEP_STREAMS) {
            if (stream in muted || muter.isMuted(stream)) continue
            if (stream == AudioManager.STREAM_MUSIC && muter.isMusicActive()) {
                record("left ${name(stream)} alone: media is playing")
                continue
            }
            muter.setMuted(stream, true)
            muted += stream
            store.save(muted.toSet())
            record("muted ${name(stream)}")
        }
    }

    /** Puts back the streams muted for the restart. Safe to call when nothing is muted. */
    fun restoreRestart() {
        if (muted.isEmpty()) return
        for (stream in muted) {
            muter.setMuted(stream, false)
            record("restored ${name(stream)}")
        }
        muted.clear()
        store.save(emptySet())
    }

    /** Ends the dictation: restores any muted stream and releases audio focus. */
    fun end() {
        restoreRestart()
        if (!enabled || !focusHeld) return
        focus.release()
        focusHeld = false
        record("audio focus released")
    }

    private fun record(event: String) {
        events += "+${now() - startedAt} ms: $event"
    }

    companion object {
        /** Streams the recognizer's beep might play on. Music is only muted when nothing is playing on it. */
        val BEEP_STREAMS = listOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_MUSIC)

        /** Restores streams left muted by an earlier service that was killed mid-restart. */
        fun restoreLeftovers(muter: StreamMuter, store: MutedStreamStore): List<String> {
            val leftover = store.load()
            if (leftover.isEmpty()) return emptyList()
            val restored = leftover.map { stream ->
                muter.setMuted(stream, false)
                "restored ${name(stream)} left muted by an earlier dictation"
            }
            store.save(emptySet())
            return restored
        }

        fun name(stream: Int): String = when (stream) {
            AudioManager.STREAM_NOTIFICATION -> "notification stream"
            AudioManager.STREAM_SYSTEM -> "system stream"
            AudioManager.STREAM_MUSIC -> "music stream"
            else -> "stream $stream"
        }
    }
}
