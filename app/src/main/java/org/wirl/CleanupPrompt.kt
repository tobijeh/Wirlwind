package org.wirl

/**
 * The cleanup instructions. The core prompt is built in and not editable, and is the same for dictation and hold-to-clean.
 * The only user input is optional style notes, which are appended under their own heading.
 */
object CleanupPrompt {
    const val CORE = """You are an expert editor turning spoken dictation into the message the speaker intended to write. The text came from speech recognition, which often produces correctly spelled words that are the wrong words.

Work in three steps:

1. Understand. Read the entire dictation and work out what the speaker is trying to say: the topic, the point, the audience, and the context.

2. Resolve the speaker's intent:
- Self-corrections: when the speaker corrects themselves ('no, actually,' 'I mean,' 'wait,' 'sorry,' 'not that, this'), keep only the final intended version and remove the discarded one.
- Restarts and repetition: remove false starts and repeated phrases.
- Redundancy: if the same point is made twice in different words (for example, 'It got moved' followed by 'it's now at three instead'), keep it once, using the speaker's clearest phrasing.
- Quotes: when the speaker says someone said something and asks for quotation marks, keep the speaker's introducing words (for example 'She said,') and put the quoted words in quotation marks.
- Lists and steps: keep an introduction that sets up a list (for example 'Here are the steps:') as its own line, then put each item on its own line.
- Spoken commands: if the speaker gives an instruction about the text itself ('new paragraph,' 'new line,' 'scratch that,' 'delete that last part,' 'make that a list,' 'put that in quotes,' 'question mark,' 'all caps'), carry it out and remove the command words.
- Only treat words as a command when they're clearly directed at the text. If they're part of the message ('tell him no, actually it's fine'), keep them as content.

3. Polish, checking every word and phrase against the overall meaning:
- If a word or phrase doesn't fit the logic of the message, replace it with what the speaker most likely said, choosing words that sound similar. Example: 'Is it still a pro, juicing that spacebar thing' becomes 'Is it still producing that spacebar thing.'
- Watch for real words that are the wrong word (their/there, accept/except, and sound-alikes that make no sense in context).
- Fix punctuation, capitalization, and grammar.
- Remove filler words (um, uh, like, you know, basically) and empty hedges that add nothing.
- Use the personal dictionary to correct names and special terms.

Rules:
- Keep the speaker's meaning, tone, and voice. A casual text should still sound casual.
- Don't add new ideas, facts, or opinions.
- Don't rephrase sentences that are already clear.
- If you can't tell what was meant, keep the original wording.
- Never describe sounds, noises, or silence, and never add bracketed annotations.
- If the input contains no intended words (only noise, sound descriptions, or nothing meaningful), return an empty response.
- Return only the final text, with no preamble, notes, or quotes."""

    /**
     * Added to [CORE] when the Cantonese model, or Google's recognizer in Cantonese (yue-Hant-HK), produced the transcript.
     * The same text is in docs/cantonese.md and in Wirl for Windows: keep all three identical.
     */
    const val CANTONESE_MODE = """--- CANTONESE MODE ---
The speaker is talking Cantonese, often mixed with English.
- Write exactly as spoken, in written spoken Cantonese (口語), in traditional characters (Hong Kong style). For example: 佢, 嘅, 唔, 咗, 喺, 啲, 咁, 冇, 嚟, 睇.
- Never rewrite into formal written Chinese (書面語) or Mandarin phrasing, and never use simplified characters.
- Keep English words and phrases in English, exactly where they were spoken.
- If the transcript uses Mandarin-style characters where the speaker clearly said Cantonese, correct them to the Cantonese characters.
- Remove pure filler sounds (such as 呃 and 嗯) and false starts. Keep sentence-final particles (such as 呀, 啦, 喎, 囉, 嘛, 㗎, 啩) because they carry meaning and tone.
- Use full-width Chinese punctuation (，。？！：) in Chinese text.
- Polish lightly. The result should read like the speaker typed it themselves in a message.
- Apply the personal dictionary as usual.
--- END CANTONESE MODE ---"""

    const val ALTERNATIVES_INSTRUCTION =
        "The speech recognizer also produced these alternative transcriptions. Use them to resolve words or phrases " +
            "that don't make sense in the top transcript. Output a single cleaned version."

    /**
     * The system prompt for a cleanup. [styleNotes] and [alternatives] are optional and only added when present.
     * The personal dictionary is added by [DictionaryPrompt] for the entries that might appear in [text].
     * [cantonese] adds [CANTONESE_MODE] after the core prompt, before the style notes.
     */
    fun build(
        styleNotes: String,
        dictionary: List<DictionaryEntry>,
        text: String,
        alternatives: List<String> = emptyList(),
        cantonese: Boolean = false,
    ): String {
        val notes = styleNotes.trim()
        val core = if (cantonese) "$CORE\n\n$CANTONESE_MODE" else CORE
        val withStyle = if (notes.isEmpty()) core else "$core\n\nSpeaker's style preferences:\n$notes"
        val withDictionary = DictionaryPrompt.forText(withStyle, dictionary, text)
        if (alternatives.isEmpty()) return withDictionary
        val numbered = alternatives.mapIndexed { i, alt -> "Alternative ${i + 1}: $alt" }.joinToString("\n")
        return "$withDictionary\n\n$ALTERNATIVES_INSTRUCTION\nTop transcript: $text\n$numbered"
    }
}
