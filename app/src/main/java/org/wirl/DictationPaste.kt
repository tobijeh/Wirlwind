package org.wirl

/** The clipboard, generic over its clip type so tests can use strings. Android uses ClipData. */
interface ClipboardPort<T> {
    fun current(): T?
    fun set(text: String)
    /** Puts back what was there before. Null means the clipboard was empty. */
    fun restore(saved: T?)
}

/** The focused field, as the insertion needs it. */
interface FieldPort {
    fun paste(): Boolean
    fun setText(text: String, cursor: Int): Boolean
}

enum class InsertMethod { PASTE, SET_TEXT, FAILED }

object DictationPaste {
    /**
     * Inserts [piece] at the cursor by putting it on the clipboard and pasting. The user's clipboard is put back
     * afterwards, whatever happened. Only if pasting fails is the whole field rewritten with [fallbackText].
     */
    fun <T> insert(
        clipboard: ClipboardPort<T>,
        field: FieldPort,
        piece: String,
        fallbackText: () -> Pair<String, Int>?,
    ): InsertMethod {
        val saved = clipboard.current()
        clipboard.set(piece)
        val pasted = field.paste()
        clipboard.restore(saved)
        if (pasted) return InsertMethod.PASTE
        val fallback = fallbackText() ?: return InsertMethod.FAILED
        return if (field.setText(fallback.first, fallback.second)) InsertMethod.SET_TEXT else InsertMethod.FAILED
    }
}
