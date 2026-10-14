package org.wirl

/** Decides whether a field counts as empty before dictation, so no leading space is ever added to it. */
object FieldState {
    fun wasEmpty(
        text: String?,
        hint: String?,
        contentDescription: String?,
        showingHint: Boolean,
        selectionStart: Int,
        selectionEnd: Int,
    ): Boolean =
        text.isNullOrBlank() ||
            showingHint ||
            text == hint ||
            text == contentDescription ||
            FieldText.visible(text, hint, showingHint, selectionStart, selectionEnd, contentDescription).isEmpty() ||
            selectionStart == 0
}

/** Safety net, run after inserting into a field that was empty: removes only leading whitespace. */
object LeadingWhitespaceGuard {
    /** The corrected text and cursor, or null if nothing needed fixing. */
    fun correct(wasEmpty: Boolean, current: String): String? {
        if (!wasEmpty || current.isEmpty() || !current.first().isWhitespace()) return null
        return current.dropWhile { it.isWhitespace() }
    }
}

/**
 * Shows whitespace in diagnostics so it can be seen: spaces are dots, newlines are ⏎, tabs are arrows, and the value
 * is wrapped in brackets, e.g. [·Hi·there].
 */
object Visible {
    fun mark(value: String?): String {
        if (value == null) return "null"
        val shown = value.map { c ->
            when (c) {
                ' ' -> '·'
                '\n' -> '⏎'
                '\t' -> '→'
                else -> c
            }
        }.joinToString("")
        return "[$shown]"
    }
}
