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

    /** Threads for transcription: leave two cores for the system and recording, and stay between 2 and 6. */
    fun threadsFor(cores: Int): Int = (cores - 2).coerceIn(2, 6)

    /**
     * The language to ask Whisper for. The English models only do English. Multilingual uses the dictation language
     * (for example "de" from "de-DE"), or detects it when there's none.
     */
    fun languageFor(model: WhisperModel, dictationTag: String): String {
        if (!model.multilingual) return "en"
        return dictationTag.substringBefore('-').lowercase().ifEmpty { "auto" }
    }
}
