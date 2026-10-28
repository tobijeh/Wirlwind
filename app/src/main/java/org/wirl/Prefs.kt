package org.wirl

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Settings storage. The API key lives in EncryptedSharedPreferences; everything else is plain. */
class Prefs(context: Context) {
    private val app = context.applicationContext

    val plain: SharedPreferences = app.getSharedPreferences("wirl", Context.MODE_PRIVATE)

    private val secure: SharedPreferences by lazy { secureFactory(app) }

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
        if (plain.getInt(KEY_SETTINGS_VERSION, 1) < 3) {
            // Version 3: the cleanup prompt is built in. Earlier custom prompts are discarded, not kept as style notes.
            plain.edit().remove(KEY_PROMPT).putInt(KEY_SETTINGS_VERSION, 3).apply()
        }
    }

    /** Run the dictated text through the cleanup instructions. Off inserts exactly what was said. */
    var dictationCleanup: Boolean
        get() = plain.getBoolean(KEY_DICTATION_CLEANUP, true)
        set(value) = plain.edit().putBoolean(KEY_DICTATION_CLEANUP, value).apply()

    /** Which engine turns speech into text. Whisper by default, with Google's recognizer as the fallback. */
    var dictationEngine: DictationEngine
        get() = DictationEngine.fromKey(plain.getString(KEY_DICTATION_ENGINE, null))
        set(value) = plain.edit().putString(KEY_DICTATION_ENGINE, value.key).apply()

    /** The Whisper model. Fast (base.en) by default. */
    var whisperModel: WhisperModel
        get() = WhisperModel.fromKey(plain.getString(KEY_WHISPER_MODEL, null))
        set(value) = plain.edit().putString(KEY_WHISPER_MODEL, value.name).apply()

    /** Shows the Diagnostics screen in Settings. Logging runs either way. */
    var developerOptions: Boolean
        get() = plain.getBoolean(KEY_DEVELOPER_OPTIONS, false)
        set(value) = plain.edit().putBoolean(KEY_DEVELOPER_OPTIONS, value).apply()

    /** Threads for Whisper transcription. Four matches the performance cores on most phones. */
    var whisperThreads: Int
        get() = plain.getInt(KEY_WHISPER_THREADS, WhisperPrompt.DEFAULT_THREADS).coerceIn(1, 8)
        set(value) = plain.edit().putInt(KEY_WHISPER_THREADS, value.coerceIn(1, 8)).apply()

    /** Whether model downloads may use mobile data. Off by default: downloads wait for Wi-Fi. */
    var allowMobileData: Boolean
        get() = plain.getBoolean(KEY_ALLOW_MOBILE_DATA, false)
        set(value) = plain.edit().putBoolean(KEY_ALLOW_MOBILE_DATA, value).apply()

    /** Whether the one-time "download the speech model" offer has been shown. */
    var modelOfferShown: Boolean
        get() = plain.getBoolean(KEY_MODEL_OFFER_SHOWN, false)
        set(value) = plain.edit().putBoolean(KEY_MODEL_OFFER_SHOWN, value).apply()

    /** The streams muted for a restart, kept so they can be restored if the service is killed while they're muted. */
    val mutedStreams: MutedStreamStore = object : MutedStreamStore {
        override fun load(): Set<Int> =
            plain.getString(KEY_MUTED_STREAMS, "").orEmpty().split(",").mapNotNull { it.toIntOrNull() }.toSet()

        override fun save(streams: Set<Int>) {
            plain.edit().putString(KEY_MUTED_STREAMS, streams.joinToString(",")).apply()
        }
    }

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

    /** Optional notes about how the user writes, appended to the built-in cleanup prompt. Empty by default. */
    var styleNotes: String
        get() = plain.getString(KEY_STYLE_NOTES, "").orEmpty()
        set(value) = plain.edit().putString(KEY_STYLE_NOTES, value.trim()).apply()

    /** Set once the first-run checklist has been shown, so it opens once automatically. */
    var setupShown: Boolean
        get() = plain.getBoolean(KEY_SETUP_SHOWN, false)
        set(value) = plain.edit().putBoolean(KEY_SETUP_SHOWN, value).apply()

    /** Whether the last dictation test found a working recognizer. Read by the setup checklist. */
    var engineTestPassed: Boolean
        get() = plain.getBoolean(KEY_ENGINE_TEST_PASSED, false)
        set(value) = plain.edit().putBoolean(KEY_ENGINE_TEST_PASSED, value).apply()

    /** Bubble diameter in dp, 40 to 80. */
    var bubbleSizeDp: Int
        get() = plain.getInt(KEY_BUBBLE_SIZE, DEFAULT_BUBBLE_SIZE_DP).coerceIn(MIN_BUBBLE_SIZE_DP, MAX_BUBBLE_SIZE_DP)
        set(value) = plain.edit().putInt(KEY_BUBBLE_SIZE, value).apply()

    /** Bubble opacity in percent, 30 to 100. */
    var bubbleOpacity: Int
        get() = plain.getInt(KEY_BUBBLE_OPACITY, DEFAULT_BUBBLE_OPACITY).coerceIn(MIN_BUBBLE_OPACITY, MAX_BUBBLE_OPACITY)
        set(value) = plain.edit().putInt(KEY_BUBBLE_OPACITY, value).apply()

    /** Which glyph the floating bubble shows. Unknown values fall back to the Wirl swirl. */
    var bubbleIcon: String
        get() = BubbleIcons.find(plain.getString(KEY_BUBBLE_ICON, BubbleIcons.DEFAULT)).id
        set(value) = plain.edit().putString(KEY_BUBBLE_ICON, value).apply()

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

    /** Puts every setting back to its default. Keeps the API key and the dictionary. */
    fun resetToDefaults() {
        plain.edit().apply { RESETTABLE_KEYS.forEach { remove(it) } }.apply()
    }

    companion object {
        /**
         * Opens the encrypted store. Tests replace it with an in-memory store, because Robolectric has no Android keystore.
         */
        var secureFactory: (Context) -> SharedPreferences = { app ->
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

        const val KEY_API_KEY = "api_key"
        const val KEY_BUBBLE_ENABLED = "bubble_enabled"
        const val KEY_PROMPT = "system_prompt"
        const val KEY_BUBBLE_X = "bubble_x"
        const val KEY_BUBBLE_Y = "bubble_y"
        const val KEY_BUBBLE_SIZE = "bubble_size_dp"
        const val KEY_BUBBLE_OPACITY = "bubble_opacity"
        const val KEY_BUBBLE_ICON = "bubble_icon"
        const val KEY_LAST_RESULT = "last_result"
        const val KEY_DICTIONARY = "dictionary"
        const val KEY_DICTATION_CLEANUP = "dictation_cleanup"
        const val KEY_DICTATION_ENGINE = "dictation_engine"
        const val KEY_WHISPER_MODEL = "whisper_model"
        const val KEY_ALLOW_MOBILE_DATA = "allow_mobile_data"
        const val KEY_DEVELOPER_OPTIONS = "developer_options"
        const val KEY_WHISPER_THREADS = "whisper_threads"
        const val KEY_MODEL_OFFER_SHOWN = "model_offer_shown"
        const val KEY_MUTED_STREAMS = "muted_streams"
        const val KEY_DIAGNOSTICS = "diagnostics"
        const val KEY_SETTINGS_VERSION = "settings_version"
        const val KEY_STYLE_NOTES = "style_notes"
        const val KEY_ENGINE_TEST_PASSED = "engine_test_passed"
        const val KEY_SETUP_SHOWN = "setup_shown"

        /** Settings reset by "Reset to defaults". The API key and the dictionary are kept. */
        val RESETTABLE_KEYS = listOf(
            KEY_BUBBLE_ENABLED, KEY_BUBBLE_X, KEY_BUBBLE_Y, KEY_BUBBLE_SIZE, KEY_BUBBLE_OPACITY, KEY_BUBBLE_ICON,
            KEY_SHOW_DONE_CHECK, KEY_SHOW_DONE_MESSAGE, KEY_STYLE_NOTES, KEY_DICTATION_CLEANUP,
            KEY_DICTATION_LANGUAGE, KEY_PROMPT, KEY_DICTATION_ENGINE, KEY_WHISPER_MODEL, KEY_ALLOW_MOBILE_DATA,
            KEY_DEVELOPER_OPTIONS, KEY_WHISPER_THREADS,
        )
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

    }
}
