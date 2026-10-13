package org.wirl

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cleans text using the saved key, instructions, and dictionary. Used by the bubble and the selection menu. */
object ClaudeClient {
    private val api = ClaudeApi()

    suspend fun clean(context: Context, text: String): String = withContext(Dispatchers.IO) {
        val prefs = Prefs(context)
        val apiKey = try {
            prefs.apiKey
        } catch (e: Exception) {
            // Encrypted storage can fail on some devices; say so instead of failing silently.
            throw CleanupException("Can't read your saved key. Paste it again in Wirl.")
        }
        if (apiKey.isBlank()) throw CleanupException("Add your API key in Wirl")

        val relevant = DictionaryMatcher.relevant(prefs.dictionary, text)
        api.clean(apiKey, DictionaryPrompt.build(prefs.systemPrompt, relevant), text)
    }
}
