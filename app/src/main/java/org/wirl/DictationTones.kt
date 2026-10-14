package org.wirl

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * The start and end sounds of a Whisper dictation: one short tone each. Whisper doesn't beep between pauses, so these are
 * the only sounds the dictation makes.
 */
object DictationTones {
    const val START = ToneGenerator.TONE_PROP_BEEP
    const val END = ToneGenerator.TONE_PROP_BEEP2

    fun play(tone: Int) {
        runCatching {
            val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            generator.startTone(tone, 150)
            Handler(Looper.getMainLooper()).postDelayed({ generator.release() }, 300)
        }
    }
}
