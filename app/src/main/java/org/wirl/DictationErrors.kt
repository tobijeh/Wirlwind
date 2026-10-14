package org.wirl

/** What went wrong in dictation, in words that can be shown to the user. */
enum class DictationError(val message: String) {
    NO_PERMISSION("No mic permission. Allow the microphone for Wirl in Settings."),
    MIC_BLOCKED("Mic blocked by Android. Another app may be using it, or Wirl can't use it in the background."),
    RECOGNIZER_UNAVAILABLE("Speech recognition isn't available. Open Wirl settings to see what's missing."),
    NO_SPEECH("No speech heard."),
    BUSY("Speech recognizer is busy. Try again in a moment."),
    NETWORK("Network error. Check your internet connection."),
    NETWORK_TIMEOUT("Network timed out while recognizing speech."),
    TOO_MANY_REQUESTS("Too many speech requests. Wait a moment and try again."),
    SERVER("Speech server error. Try again."),
    CLIENT("Speech client error inside Android."),
    LANGUAGE("This language isn't supported, or its speech pack isn't downloaded. Change the language in Wirl settings."),
    OTHER("Dictation stopped unexpectedly."),
    ;

    companion object {
        /** Maps a SpeechRecognizer.ERROR_* code (values from android.speech.SpeechRecognizer). */
        fun from(code: Int): DictationError = when (code) {
            1 -> NETWORK_TIMEOUT // ERROR_NETWORK_TIMEOUT
            2 -> NETWORK // ERROR_NETWORK
            3 -> MIC_BLOCKED // ERROR_AUDIO
            4 -> SERVER // ERROR_SERVER
            5 -> CLIENT // ERROR_CLIENT
            6, 7 -> NO_SPEECH // ERROR_SPEECH_TIMEOUT, ERROR_NO_MATCH
            8 -> BUSY // ERROR_RECOGNIZER_BUSY
            9 -> NO_PERMISSION // ERROR_INSUFFICIENT_PERMISSIONS
            10 -> TOO_MANY_REQUESTS // ERROR_TOO_MANY_REQUESTS
            11 -> SERVER // ERROR_SERVER_DISCONNECTED
            12, 13 -> LANGUAGE // ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE
            else -> OTHER
        }

        /** Maps an exception thrown while starting or running dictation. */
        fun from(throwable: Throwable): DictationError = when (throwable) {
            // Android throws SecurityException when the microphone service can't start in the background.
            is SecurityException -> MIC_BLOCKED
            else -> if (throwable.javaClass.simpleName.contains("ForegroundServiceStart")) MIC_BLOCKED else OTHER
        }

        /** The message for a failure. Unknown codes include the number so they can be looked up. */
        fun messageFor(error: DictationError, code: Int?): String =
            if (error == OTHER && code != null) "Speech error code $code." else error.message
    }
}

/** What to do after a recognizer error, while nothing has been heard yet. */
sealed interface Recovery {
    data object None : Recovery
    /** The recognizer is busy: destroy it and make a new one with the same engine. */
    data class RecreateSame(val engine: SpeechEngine.Choice) : Recovery
    /** The engine failed: try the next available engine. */
    data class Fallback(val engine: SpeechEngine.Choice) : Recovery
}

object RecoveryPlanner {
    const val MAX_BUSY_RETRIES = 2

    /** Errors that mean "this engine can't do it, another might". */
    private val engineFailures = setOf(
        DictationError.NETWORK_TIMEOUT,
        DictationError.NETWORK,
        DictationError.SERVER,
        DictationError.CLIENT,
        DictationError.TOO_MANY_REQUESTS,
        DictationError.LANGUAGE,
        DictationError.OTHER,
    )

    /**
     * [available] is in preference order. Any engine failure moves on to the next engine that hasn't been tried,
     * including a language error from the on-device recognizer, so dictation never depends on it alone.
     * Once anything has been heard, no recovery is attempted: the text so far is kept.
     */
    fun plan(
        code: Int,
        transcriptEmpty: Boolean,
        current: SpeechEngine.Choice,
        tried: Set<SpeechEngine.Choice>,
        available: List<SpeechEngine.Choice>,
        busyRetries: Int,
    ): Recovery {
        if (!transcriptEmpty) return Recovery.None
        val error = DictationError.from(code)
        return when {
            error == DictationError.BUSY ->
                if (busyRetries < MAX_BUSY_RETRIES) Recovery.RecreateSame(current) else Recovery.None
            error in engineFailures || error == DictationError.LANGUAGE ->
                available.firstOrNull { it !in tried }?.let { Recovery.Fallback(it) } ?: Recovery.None
            else -> Recovery.None
        }
    }
}

/** One attempt at dictation, as shown in Diagnostics. */
data class DiagnosticEntry(
    val time: Long,
    val engines: String,
    val errorCode: Int?,
    val message: String,
    val stackTrace: String?,
)

object DiagnosticsLog {
    const val MAX_ENTRIES = 20

    /** Adds [entry] and keeps only the newest [MAX_ENTRIES]. */
    fun add(entries: List<DiagnosticEntry>, entry: DiagnosticEntry): List<DiagnosticEntry> =
        (entries + entry).takeLast(MAX_ENTRIES)

    /** Plain text for the Copy button: newest first. */
    fun copyText(entries: List<DiagnosticEntry>): String {
        if (entries.isEmpty()) return "No dictation attempts yet."
        return entries.asReversed().joinToString("\n\n") { e ->
            buildString {
                append(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(e.time)))
                append("\nRecognizer: ").append(e.engines.ifBlank { "none" })
                append("\nError code: ").append(e.errorCode?.toString() ?: "none")
                append("\nResult: ").append(e.message)
                e.stackTrace?.let { append("\n").append(it) }
            }
        }
    }
}
