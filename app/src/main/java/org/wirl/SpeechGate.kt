package org.wirl

/**
 * Decides whether a dictation inserts anything. Silence, noise, and sound descriptions never reach Claude and never
 * reach the field. Dictation is for words, so a recording without words is discarded quietly: nothing is inserted and
 * nothing is shown.
 */
object SpeechGate {
    /** Fewer letters than this is not a sentence. It's noise or a stray sound, so Claude isn't called. */
    const val MIN_LETTERS = 3

    /** Replies that talk about the recording instead of giving text. The whole reply must be one of these. */
    private val META_REPLY = Regex(
        "^(?:no (?:speech|words|audio|intended words)(?: (?:was |were )?(?:detected|heard|found|recorded))?|" +
            "nothing (?:was )?(?:said|spoken|heard)|silence|silent|" +
            "(?:the )?(?:audio|recording|speech) (?:is|was|seems|sounds) (?:unclear|silent|empty|inaudible)|" +
            "unclear (?:audio|speech)(?:, please try again)?|inaudible|" +
            "i (?:can't|cannot|couldn't|could not) (?:hear|make out) (?:anything|the audio|you)|" +
            "(?:please )?try (?:recording|speaking) again|sorry,? i (?:didn't|did not|can't|cannot) hear)[.!]?$",
        RegexOption.IGNORE_CASE,
    )

    /** The words that were spoken. Empty when nothing was said. */
    fun spokenWords(transcript: String): String = TranscriptFilter.clean(transcript)

    /** Claude is called only when there are enough letters to be a sentence. */
    fun callsClaude(spoken: String): Boolean = spoken.count { it.isLetter() } >= MIN_LETTERS

    /**
     * True when Claude's reply is a remark about the recording ("No speech detected.", "Unclear audio, please try again.")
     * instead of text for the field. Only short replies are checked, so a real sentence that mentions silence is kept.
     */
    fun isRecordingRemark(reply: String): Boolean {
        val text = reply.trim()
        if (text.isEmpty() || text.split(Regex("\\s+")).size > MAX_REMARK_WORDS) return false
        return META_REPLY.containsMatchIn(text)
    }

    private const val MAX_REMARK_WORDS = 12

    /**
     * The text to insert, or null when nothing should be inserted. A cleanup that replied with nothing, or with a remark
     * about the recording, inserts nothing. A cleanup that failed, or cleanup that is off, inserts the spoken words as they were.
     */
    fun textToInsert(spoken: String, aiEnabled: Boolean, cleaned: Result<String>?): String? {
        if (!aiEnabled || cleaned == null) return spoken
        cleaned.onSuccess { reply ->
            return reply.takeIf { it.isNotBlank() && !isRecordingRemark(it) }
        }
        return spoken
    }
}
