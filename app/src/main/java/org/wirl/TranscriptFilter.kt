package org.wirl

/**
 * Turns a transcript into the words that were actually spoken. Wirl is for dictation, not captions, so sound annotations
 * ([BLANK_AUDIO], [beep], (music), *sighs*) are removed, and so are the phrases Whisper tends to invent over silence or
 * noise. A note is removed only when it names a sound. "(see below)" is kept.
 */
object TranscriptFilter {
    /** Words that name a sound or silence. A bracketed or parenthesized note containing one is an annotation. */
    private val SOUND_WORDS = setOf(
        "beep", "beeps", "beeping", "music", "noise", "noises", "silence", "silent", "applause", "laughter", "laughs",
        "laughing", "sigh", "sighs", "sighing", "cough", "coughs", "coughing", "whoosh", "whooshing", "whistle",
        "whistling", "typing", "clicking", "clicks", "inaudible", "singing", "humming", "ringing", "buzzing", "chime",
        "breathing", "gasps", "groans", "footsteps", "muffled", "mumbles", "barking", "thunder", "crowd", "static",
    )

    /** Known Whisper inventions on silence or noise. Dropped only when they make up the entire text. */
    private val SILENCE_PHRASES = setOf(
        "thank you", "thank you very much", "thanks", "thanks for watching", "thank you for watching",
        "please subscribe", "subscribe", "bye", "bye bye", "you",
    )

    private val ANNOTATION = Regex("""\[([^\]\n]*)]|\(([^)\n]*)\)|\*([^*\n]+)\*""")
    private val WORD = Regex("[^a-z]+")
    private val UPPER_TAG = Regex("[A-Z][A-Z_]*_[A-Z_]*")

    /** The transcript without sound annotations, with whitespace tidied. Line breaks are kept. */
    fun removeAnnotations(text: String): String {
        val stripped = ANNOTATION.replace(text) { match ->
            val bracketed = match.groups[1] != null
            val note = (match.groups[1] ?: match.groups[2] ?: match.groups[3])?.value.orEmpty()
            if (isSoundNote(note, bracketed)) "" else match.value
        }
        return stripped.lines().joinToString("\n") { it.replace(Regex("[ \t]{2,}"), " ").trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun isSoundNote(note: String, bracketed: Boolean): Boolean {
        // Tags like [BLANK_AUDIO] are Whisper's own; anything in square brackets with a sound word is an annotation too.
        if (bracketed && UPPER_TAG.matches(note.trim())) return true
        return WORD.split(note.lowercase()).any { it in SOUND_WORDS }
    }

    /** True when the whole text is one of the phrases Whisper invents over silence. */
    fun isSilencePhrase(text: String): Boolean {
        val normalized = text.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()
        return normalized.isNotEmpty() && normalized in SILENCE_PHRASES
    }

    /** The words spoken: annotations removed, and nothing if what's left is only a silence phrase. */
    fun clean(text: String): String {
        val words = removeAnnotations(text)
        return if (isSilencePhrase(words)) "" else words
    }
}
