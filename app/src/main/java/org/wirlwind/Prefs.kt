package org.wirlwind

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Settings storage. The API key lives in EncryptedSharedPreferences; everything else is plain. */
class Prefs(context: Context) {
    private val app = context.applicationContext

    val plain: SharedPreferences = app.getSharedPreferences("wirlwind", Context.MODE_PRIVATE)

    private val secure: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            "wirlwind_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var apiKey: String
        get() = secure.getString(KEY_API_KEY, "").orEmpty()
        set(value) = secure.edit().putString(KEY_API_KEY, value.trim()).apply()

    var bubbleEnabled: Boolean
        get() = plain.getBoolean(KEY_BUBBLE_ENABLED, true)
        set(value) = plain.edit().putBoolean(KEY_BUBBLE_ENABLED, value).apply()

    var systemPrompt: String
        get() = plain.getString(KEY_PROMPT, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_PROMPT
        set(value) = plain.edit().putString(KEY_PROMPT, value.trim()).apply()

    /** Saved bubble position, or null if the bubble has never been moved. */
    var bubblePosition: Pair<Int, Int>?
        get() = if (plain.contains(KEY_BUBBLE_X)) {
            plain.getInt(KEY_BUBBLE_X, 0) to plain.getInt(KEY_BUBBLE_Y, 0)
        } else null
        set(value) {
            if (value == null) return
            plain.edit().putInt(KEY_BUBBLE_X, value.first).putInt(KEY_BUBBLE_Y, value.second).apply()
        }

    companion object {
        const val KEY_API_KEY = "api_key"
        const val KEY_BUBBLE_ENABLED = "bubble_enabled"
        const val KEY_PROMPT = "system_prompt"
        const val KEY_BUBBLE_X = "bubble_x"
        const val KEY_BUBBLE_Y = "bubble_y"

        const val DEFAULT_PROMPT =
            "You clean up dictated text. Fix punctuation, capitalization, and grammar. " +
                "Remove filler words (um, uh, like, you know) and false starts. " +
                "Keep the speaker's meaning, tone, and casual voice. Do not add content. " +
                "Return only the cleaned text, with no preamble or quotes."
    }
}
