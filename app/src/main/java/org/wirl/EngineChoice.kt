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

/** Why dictation isn't using Whisper, when the user chose it. */
enum class FallbackReason {
    /** The model the user chose isn't downloaded. */
    MODEL_MISSING,
    /** The native library won't load on this device. */
    NATIVE_MISSING,
    /** No fallback: Whisper is in use, or Google was chosen. */
    NONE,
}

/** The engine dictation uses right now, and the reason when it's a fallback. */
data class EngineUse(val engine: DictationEngine, val reason: FallbackReason)

object EngineChoice {
    /**
     * Whisper when it's the preferred engine, its model is downloaded, and the native library loads. Otherwise Google's
     * recognizer, so dictation still works before a model is downloaded.
     */
    fun choose(preferred: DictationEngine, modelReady: Boolean, nativeLoads: Boolean): DictationEngine =
        use(preferred, modelReady, nativeLoads).engine

    /** The engine in use and why. Settings shows this, and Diagnostics records it. */
    fun use(preferred: DictationEngine, modelReady: Boolean, nativeLoads: Boolean): EngineUse = when {
        preferred == DictationEngine.GOOGLE -> EngineUse(DictationEngine.GOOGLE, FallbackReason.NONE)
        !nativeLoads -> EngineUse(DictationEngine.GOOGLE, FallbackReason.NATIVE_MISSING)
        !modelReady -> EngineUse(DictationEngine.GOOGLE, FallbackReason.MODEL_MISSING)
        else -> EngineUse(DictationEngine.WHISPER, FallbackReason.NONE)
    }
}
