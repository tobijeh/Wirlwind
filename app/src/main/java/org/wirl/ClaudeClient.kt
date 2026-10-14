package org.wirl

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one cleanup pipeline. Hold-to-clean and dictation both call it with the same key, instructions, and dictionary,
 * so the same text gets the same result from either.
 */
class CleanupPipeline(
    private val api: ClaudeApi,
    private val apiKey: () -> String,
    private val instructions: () -> String,
    private val dictionary: () -> List<DictionaryEntry>,
) {
    /** Returns the cleaned text, or throws [CleanupException] with a message for the user. */
    suspend fun clean(text: String): String = withContext(Dispatchers.IO) {
        val key = try {
            apiKey()
        } catch (e: Exception) {
            // Encrypted storage can fail on some devices; say so instead of failing silently.
            throw CleanupException("Can't read your saved key. Paste it again in Wirl.")
        }
        if (key.isBlank()) throw CleanupException("Add your API key in Wirl")
        api.clean(key, DictionaryPrompt.forText(instructions(), dictionary(), text), text)
    }
}

object ClaudeClient {
    /** Cleans [text] for hold-to-clean and dictation alike. */
    suspend fun clean(context: Context, text: String): String = pipeline(context).clean(text)

    private fun pipeline(context: Context) = CleanupPipeline(
        api = ClaudeApi(),
        apiKey = { Prefs(context).apiKey },
        instructions = { Prefs(context).systemPrompt },
        dictionary = { DictionaryRepository.get(context).entries.value },
    )
}
