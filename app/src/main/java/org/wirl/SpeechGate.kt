package org.wirl

/**
 * Decides whether a dictation inserts anything. Silence, noise, and sound descriptions never reach Claude and never
 * reach the field. Dictation is for words, so an empty result says "No speech heard" and leaves the field alone.
 */
object SpeechGate {
    /** The words that were spoken. Empty when nothing was said. */
    fun spokenWords(transcript: String): String = TranscriptFilter.clean(transcript)

    /** Claude is called only when there are words to clean. */
    fun callsClaude(spoken: String): Boolean = spoken.isNotBlank()

    /**
     * The text to insert, or null when nothing should be inserted. A cleanup that replied with nothing inserts nothing. A
     * cleanup that failed, or cleanup that is off, inserts the spoken words as they were.
     */
    fun textToInsert(spoken: String, aiEnabled: Boolean, cleaned: Result<String>?): String? {
        if (!aiEnabled || cleaned == null) return spoken
        cleaned.onSuccess { return it.takeIf { text -> text.isNotBlank() } }
        return spoken
    }
}
