package org.wirl

/**
 * Decides what text is really in an input field. Placeholder (hint) text must never be treated as something the
 * user wrote, so it isn't cleaned, replaced, or sent to Claude.
 */
object FieldText {
    /**
     * The text the user wrote, or "" if the field is empty, blank, or showing its hint.
     * A field shows its hint when the platform says so, or when its text equals the hint and the cursor sits at the
     * start. Typed text normally leaves the cursor after it, so a typed "Message" is kept.
     */
    fun visible(text: String?, hint: String?, showingHint: Boolean, selectionStart: Int, selectionEnd: Int): String {
        if (text == null || showingHint || text.isBlank()) return ""
        val isHint = hint != null && text == hint && selectionStart <= 0
        return if (isHint) "" else text
    }
}
