package org.wirl

/** Decides whether dictated text may get a space before it. A field the app can't read confidently never gets one. */
object FieldState {
    /** Whether a space may go before dictated text. Needs a real cursor, no hint showing, and a non-space character before the cursor. */
    fun mayAddLeadingSpace(
        text: String?,
        hint: String?,
        contentDescription: String?,
        showingHint: Boolean,
        selectionStart: Int,
        selectionEnd: Int,
    ): Boolean {
        if (!FieldText.hasCursor(selectionStart, selectionEnd) || showingHint) return false
        val existing = FieldText.visible(text, hint, showingHint, selectionStart, selectionEnd, contentDescription)
        val cursor = minOf(selectionStart, selectionEnd).coerceAtMost(existing.length)
        val before = existing.substring(0, cursor)
        return before.isNotEmpty() && !before.last().isWhitespace()
    }
}

/** Safety net, run after inserting into a field where no leading space was allowed: removes only leading whitespace. */
object LeadingWhitespaceGuard {
    /** The corrected text, or null if nothing needed fixing. */
    fun correct(leadingSpaceAllowed: Boolean, current: String): String? {
        if (leadingSpaceAllowed || current.isEmpty() || !current.first().isWhitespace()) return null
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
