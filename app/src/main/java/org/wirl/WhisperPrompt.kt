package org.wirl

/** Settings for Whisper that depend on the dictionary, the device, or the dictation language. */
object WhisperPrompt {
    /** whisper.cpp keeps only a short prompt. Longer lists are cut so the most important terms come first. */
    const val MAX_CHARS = 400

    /**
     * Dictionary terms as the model's initial prompt, so names and special words are recognized as they're heard.
     * Null when there are no terms.
     */
    fun build(terms: List<String>): String? {
        val kept = mutableListOf<String>()
        var length = 0
        for (term in terms.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
            val added = if (kept.isEmpty()) term.length else length + 2 + term.length
            if (added > MAX_CHARS) break
            kept += term
            length = added
        }
        if (kept.isEmpty()) return null
        return "Glossary: ${kept.joinToString(", ")}."
    }

    /**
     * Threads for transcription, the same for every model. Four is the number of performance cores on most phones. More
     * than that reaches the efficiency cores, which slow the whole transcription down. It changes speed only, never
     * accuracy, so it isn't a setting.
     */
    const val MAX_THREADS = 4

    /** Four threads, or one per core on a phone with fewer than four. */
    fun threadsFor(cores: Int): Int = cores.coerceIn(1, MAX_THREADS)

    /** The language the model is told: "en" for the English models, "yue" for Cantonese, "zh" for Mandarin. */
    fun languageFor(model: WhisperModel): String = model.language.modelCode
}
