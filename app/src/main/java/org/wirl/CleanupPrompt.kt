package org.wirl

/**
 * The cleanup instructions. The core prompt is built in and not editable. The only user input is optional style
 * notes, which are appended under their own heading.
 */
object CleanupPrompt {
    const val CORE = """You clean up text that was dictated by voice and transcribed by speech recognition. Speech recognition often produces correctly spelled words that are the wrong words.

Work in two steps:

1. Understand first. Read the entire message and work out what the speaker is trying to say: the topic, the point, and the context.

2. Then correct, checking every word and phrase against that overall meaning:
- If a word or phrase doesn't fit the logic of the message, replace it with what the speaker most likely said, choosing words that sound similar. Example: 'Is it still a pro, juicing that spacebar thing' becomes 'Is it still producing that spacebar thing'.
- Watch for real words that are the wrong word (their/there, accept/except, and sound-alikes that make no sense in context).
- Fix punctuation, capitalization, and grammar.
- Remove filler words (um, uh, like, you know) and false starts, keeping the speaker's final intended version.
- Use the personal dictionary to correct names and special terms.

Rules:
- Keep the speaker's meaning, tone, and casual voice.
- Don't add new ideas, and don't rephrase things that already make sense.
- If you can't tell what was meant, keep the original wording.
- Return only the cleaned text, with no preamble or quotes."""

    const val ALTERNATIVES_INSTRUCTION =
        "The speech recognizer also produced these alternative transcriptions. Use them to resolve words or phrases " +
            "that don't make sense in the top transcript. Output a single cleaned version."

    /**
     * The system prompt for a cleanup. [styleNotes] and [alternatives] are optional and only added when present.
     * The personal dictionary is added by [DictionaryPrompt] for the entries that might appear in [text].
     */
    fun build(
        styleNotes: String,
        dictionary: List<DictionaryEntry>,
        text: String,
        alternatives: List<String> = emptyList(),
    ): String {
        val notes = styleNotes.trim()
        val withStyle = if (notes.isEmpty()) CORE else "$CORE\n\nSpeaker's style preferences:\n$notes"
        val withDictionary = DictionaryPrompt.forText(withStyle, dictionary, text)
        if (alternatives.isEmpty()) return withDictionary
        val numbered = alternatives.mapIndexed { i, alt -> "Alternative ${i + 1}: $alt" }.joinToString("\n")
        return "$withDictionary\n\n$ALTERNATIVES_INSTRUCTION\nTop transcript: $text\n$numbered"
    }
}
