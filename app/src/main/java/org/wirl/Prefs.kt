package org.wirl

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Settings storage. The API key lives in EncryptedSharedPreferences; everything else is plain. */
class Prefs(context: Context) {
    private val app = context.applicationContext

    val plain: SharedPreferences = app.getSharedPreferences("wirl", Context.MODE_PRIVATE)

    private val secure: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            "wirl_secure",
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

    init {
        // Settings version 2 turns dictation cleanup on for everyone once. Earlier versions could have saved it as off.
        if (plain.getInt(KEY_SETTINGS_VERSION, 1) < 2) {
            plain.edit().putBoolean(KEY_DICTATION_CLEANUP, true).putInt(KEY_SETTINGS_VERSION, 2).apply()
        }
    }

    /** Run the dictated text through the cleanup instructions. Off inserts exactly what was said. */
    var dictationCleanup: Boolean
        get() = plain.getBoolean(KEY_DICTATION_CLEANUP, true)
        set(value) = plain.edit().putBoolean(KEY_DICTATION_CLEANUP, value).apply()

    /** Recognition language tag, or blank for the phone's language. */
    var dictationLanguage: String
        get() = plain.getString(KEY_DICTATION_LANGUAGE, "").orEmpty()
        set(value) = plain.edit().putString(KEY_DICTATION_LANGUAGE, value).apply()

    /** Whether the on-device language pack for [language] was last seen installed. Read by dictation. */
    fun onDevicePackInstalled(language: String): Boolean = plain.getBoolean(KEY_PACK_PREFIX + language, false)

    fun setOnDevicePackInstalled(language: String, installed: Boolean) {
        plain.edit().putBoolean(KEY_PACK_PREFIX + language, installed).apply()
    }

    /** The last dictation attempts, newest last. */
    var diagnostics: List<DiagnosticEntry>
        get() = DiagnosticsStore.decode(plain.getString(KEY_DIAGNOSTICS, "[]").orEmpty())
        set(value) = plain.edit().putString(KEY_DIAGNOSTICS, DiagnosticsStore.encode(value)).apply()

    /** Briefly turn the bubble green with a check when a cleanup finishes. */
    var showDoneCheck: Boolean
        get() = plain.getBoolean(KEY_SHOW_DONE_CHECK, true)
        set(value) = plain.edit().putBoolean(KEY_SHOW_DONE_CHECK, value).apply()

    /** Show the "Cleaned up · Hold bubble to undo." message when a cleanup finishes. */
    var showDoneMessage: Boolean
        get() = plain.getBoolean(KEY_SHOW_DONE_MESSAGE, false)
        set(value) = plain.edit().putBoolean(KEY_SHOW_DONE_MESSAGE, value).apply()

    var systemPrompt: String
        get() = plain.getString(KEY_PROMPT, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_PROMPT
        set(value) = plain.edit().putString(KEY_PROMPT, value.trim()).apply()

    /** Bubble diameter in dp, 40 to 80. */
    var bubbleSizeDp: Int
        get() = plain.getInt(KEY_BUBBLE_SIZE, DEFAULT_BUBBLE_SIZE_DP).coerceIn(MIN_BUBBLE_SIZE_DP, MAX_BUBBLE_SIZE_DP)
        set(value) = plain.edit().putInt(KEY_BUBBLE_SIZE, value).apply()

    /** Bubble opacity in percent, 30 to 100. */
    var bubbleOpacity: Int
        get() = plain.getInt(KEY_BUBBLE_OPACITY, DEFAULT_BUBBLE_OPACITY).coerceIn(MIN_BUBBLE_OPACITY, MAX_BUBBLE_OPACITY)
        set(value) = plain.edit().putInt(KEY_BUBBLE_OPACITY, value).apply()

    /** What the last cleanup did, shown on the settings screen so failures can be read later. */
    var lastResult: String
        get() = plain.getString(KEY_LAST_RESULT, "No cleanups yet").orEmpty()
        set(value) = plain.edit().putString(KEY_LAST_RESULT, value).apply()

    /** Personal dictionary, stored as JSON. Unreadable data reads as an empty dictionary. */
    var dictionary: List<DictionaryEntry>
        get() = runCatching { DictionaryJson.decode(plain.getString(KEY_DICTIONARY, "[]").orEmpty()) }
            .getOrDefault(emptyList())
        set(value) = plain.edit().putString(KEY_DICTIONARY, DictionaryJson.encode(value)).apply()

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
        const val KEY_BUBBLE_SIZE = "bubble_size_dp"
        const val KEY_BUBBLE_OPACITY = "bubble_opacity"
        const val KEY_LAST_RESULT = "last_result"
        const val KEY_DICTIONARY = "dictionary"
        const val KEY_DICTATION_CLEANUP = "dictation_cleanup"
        const val KEY_DIAGNOSTICS = "diagnostics"
        const val KEY_SETTINGS_VERSION = "settings_version"
        const val KEY_PACK_PREFIX = "onDevicePack_"
        const val KEY_DICTATION_LANGUAGE = "dictation_language"
        const val KEY_SHOW_DONE_CHECK = "show_done_check"
        const val KEY_SHOW_DONE_MESSAGE = "show_done_message"

        const val MIN_BUBBLE_SIZE_DP = 40
        const val MAX_BUBBLE_SIZE_DP = 80
        const val DEFAULT_BUBBLE_SIZE_DP = 56
        const val MIN_BUBBLE_OPACITY = 30
        const val MAX_BUBBLE_OPACITY = 100
        const val DEFAULT_BUBBLE_OPACITY = 100

        const val DEFAULT_PROMPT =
            "You clean up dictated text. Fix punctuation, capitalization, and grammar. " +
                "Remove filler words (um, uh, like, you know) and false starts. " +
                "Keep the speaker's meaning, tone, and casual voice. Do not add content. " +
                "Return only the cleaned text, with no preamble or quotes."
    }
}
