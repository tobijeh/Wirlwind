package org.wirl

/** Which engine turns speech into text. */
enum class DictationEngine(val key: String) {
    /** On-device Whisper, the default. */
    WHISPER("whisper"),

    /** Google's SpeechRecognizer, used when Whisper isn't available. */
    GOOGLE("google");

    companion object {
        fun fromKey(key: String?): DictationEngine = entries.firstOrNull { it.key == key } ?: WHISPER
    }
}

object EngineChoice {
    /**
     * Whisper when it's the preferred engine, its model is downloaded, and the native library loads. Otherwise Google's
     * recognizer, so dictation still works before a model is downloaded.
     */
    fun choose(preferred: DictationEngine, modelReady: Boolean, nativeLoads: Boolean): DictationEngine = when {
        preferred == DictationEngine.GOOGLE -> DictationEngine.GOOGLE
        !modelReady -> DictationEngine.GOOGLE
        !nativeLoads -> DictationEngine.GOOGLE
        else -> DictationEngine.WHISPER
    }

    /** Why Google is used in place of the preferred engine, for Diagnostics. Null when there's no fallback. */
    fun fallbackReason(preferred: DictationEngine, modelReady: Boolean, nativeLoads: Boolean): String? = when {
        preferred == DictationEngine.GOOGLE -> null
        !nativeLoads -> "Whisper isn't available on this device, using Google's recognizer"
        !modelReady -> "the Whisper model isn't downloaded yet, using Google's recognizer"
        else -> null
    }
}
