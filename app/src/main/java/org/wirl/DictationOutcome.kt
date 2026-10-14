package org.wirl

/** What dictation inserts: the cleaned text, or the raw transcript if cleanup was off or failed. */
sealed interface DictationOutcome {
    val text: String

    data class Cleaned(override val text: String) : DictationOutcome

    /** [reason] is set only when cleanup was on and failed. */
    data class Raw(override val text: String, val reason: String?) : DictationOutcome
}

object DictationDecision {
    /**
     * Cleanup is only skipped when the setting is off. A failed cleanup inserts the raw transcript and says why, so
     * nothing said is lost and the user can tell it wasn't cleaned.
     */
    fun decide(raw: String, aiEnabled: Boolean, cleaned: Result<String>?): DictationOutcome {
        if (!aiEnabled || cleaned == null) return DictationOutcome.Raw(raw, reason = null)
        cleaned.onSuccess { text ->
            if (text.isNotBlank()) return DictationOutcome.Cleaned(text)
            return DictationOutcome.Raw(raw, reason = "empty response")
        }
        return DictationOutcome.Raw(raw, reason = reasonFor(cleaned.exceptionOrNull()))
    }

    fun reasonFor(error: Throwable?): String = when (error) {
        is CleanupException -> error.message ?: "unknown error"
        null -> "unknown error"
        else -> "unexpected error (${error.javaClass.simpleName})"
    }
}
