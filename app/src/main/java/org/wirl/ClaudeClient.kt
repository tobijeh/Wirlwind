package org.wirl

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one cleanup function. Hold-to-clean and dictation both call it, so the same text gets the same request and result.
 * Only dictation passes recognizer [alternatives]. The model is always [ClaudeApi.MODEL].
 */
class CleanupPipeline(
    private val api: ClaudeApi,
    private val apiKey: () -> String,
    private val styleNotes: () -> String,
    private val dictionary: () -> List<DictionaryEntry>,
) {
    /** Returns the cleaned text, or throws [CleanupException] with a message for the user. */
    suspend fun clean(
        text: String,
        alternatives: List<String> = emptyList(),
        onTiming: (ClaudeCallTiming) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val key = try {
            apiKey()
        } catch (e: Exception) {
            // Encrypted storage can fail on some devices; say so instead of failing silently.
            throw CleanupException("Can't read your saved key. Paste it again in Wirl.")
        }
        if (key.isBlank()) throw CleanupException("Add your API key in Wirl")
        val system = CleanupPrompt.build(styleNotes(), dictionary(), text, alternatives)
        api.clean(key, system, text, onTiming)
    }
}

object ClaudeClient {
    /** Cleans [text]. Dictation passes its recognizer alternatives; hold-to-clean passes none. */
    suspend fun clean(
        context: Context,
        text: String,
        alternatives: List<String> = emptyList(),
        onTiming: (ClaudeCallTiming) -> Unit = {},
    ): String = pipeline(context).clean(text, alternatives, onTiming)

    /** Opens the connection to Claude in the background, so the first dictation doesn't pay for the handshake. */
    fun warmUp() {
        Thread({ ClaudeApi().warmUp() }, "wirl-claude-warmup").start()
    }

    private fun pipeline(context: Context) = CleanupPipeline(
        api = ClaudeApi(),
        apiKey = { Prefs(context).apiKey },
        styleNotes = { Prefs(context).styleNotes },
        dictionary = { DictionaryRepository.get(context).entries.value },
    )
}
