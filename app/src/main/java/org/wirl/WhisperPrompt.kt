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
     * Threads for transcription. Four is the number of performance cores on most phones. More than that reaches the
     * efficiency cores, which slow the whole transcription down. Developer options can change it for testing.
     */
    const val DEFAULT_THREADS = 4
    val THREAD_CHOICES = listOf(2, 3, 4, 5, 6, 8)

    /**
     * The language to ask Whisper for. The English models only do English. Multilingual uses the dictation language
     * (for example "de" from "de-DE"), or detects it when there's none.
     */
    fun languageFor(model: WhisperModel, dictationTag: String): String {
        if (!model.multilingual) return "en"
        return dictationTag.substringBefore('-').lowercase().ifEmpty { "auto" }
    }
}
