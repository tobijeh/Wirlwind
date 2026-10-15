package org.wirl

/** What the bubble shows while recording. */
enum class LivePreview {
    /** Only the red bubble. Whisper dictation: its chunks are transcribed in the background and nothing is shown until insert. */
    NONE,

    /** Google's recognizer's words, as they're spoken. */
    WORDS,
}

object DictationPreview {
    /** Whisper never shows a preview. Google's recognizer keeps its word-by-word preview. */
    fun forEngine(engine: DictationEngine): LivePreview = when (engine) {
        DictationEngine.WHISPER -> LivePreview.NONE
        DictationEngine.GOOGLE -> LivePreview.WORDS
    }
}
