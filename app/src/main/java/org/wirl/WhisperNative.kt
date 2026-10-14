package org.wirl

/**
 * The whisper.cpp functions in cpp/wirl_whisper.cpp. Touching this object loads the library. On a device where that
 * fails, it throws, and dictation uses Google's recognizer instead.
 */
internal object WhisperNative {
    init {
        System.loadLibrary("wirl_whisper")
    }

    /** Loads a model file. Returns a handle, or 0 if the file can't be loaded. */
    external fun nativeInit(path: String): Long

    external fun nativeFree(handle: Long)

    /** Transcribes 16 kHz mono samples. Returns null if whisper.cpp reports an error. */
    external fun nativeTranscribe(handle: Long, pcm: FloatArray, language: String, prompt: String?, threads: Int): String?

    /** True if the native library loads on this device. Freeing handle 0 does nothing, so this only touches the library. */
    fun loads(): Boolean = runCatching { nativeFree(0) }.isSuccess
}
