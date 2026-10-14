package org.wirl

/**
 * Decides what text the user really wrote in a field. Placeholder text (the hint, or a content description such as
 * "Message") is never treated as typed text, so it isn't cleaned, replaced, or sent to Claude.
 */
object FieldText {
    /** True if [text] is the field's placeholder: its hint or content description, and not empty. */
    fun isPlaceholder(text: String?, hint: String?, contentDescription: String?): Boolean =
        !text.isNullOrEmpty() && (text == hint || text == contentDescription)

    /**
     * The text the user wrote, or "" if the field is empty, blank, or showing its placeholder.
     * A placeholder is recognised when the platform says it's showing its hint, or when the text matches the hint or
     * content description and the cursor is at the start (or unknown). Typed text normally leaves the cursor after it,
     * so a typed "Message" is kept.
     */
    fun visible(
        text: String?,
        hint: String?,
        showingHint: Boolean,
        selectionStart: Int,
        selectionEnd: Int,
        contentDescription: String? = null,
    ): String {
        if (text == null || showingHint || text.isBlank()) return ""
        val atStart = selectionStart <= 0 && selectionEnd <= 0
        if (atStart && isPlaceholder(text, hint, contentDescription)) return ""
        return text
    }
}
