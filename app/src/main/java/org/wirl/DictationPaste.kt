package org.wirl

import kotlinx.coroutines.delay

/** The clipboard, generic over its clip type so tests can use strings. Android uses ClipData. */
interface ClipboardPort<T> {
    fun current(): T?
    fun set(text: String)

    /**
     * A moment after pasting, puts [saved] back (null means the clipboard was empty), but only while the clipboard still
     * holds [ours], the dictation. If something else was copied meanwhile, that's left alone. [done] says what happened.
     */
    fun restoreIfStillOurs(saved: T?, ours: String, done: (ClipboardRestore) -> Unit)
}

/** The focused field, as the insertion needs it. */
interface FieldPort {
    fun paste(): Boolean
    fun setText(text: String, cursor: Int): Boolean

    /** The field's text now, read fresh. Null when it can't be read: the field is gone or has lost focus. */
    fun readText(): String?
}

enum class InsertMethod { PASTE, SET_TEXT, FAILED }

/** Whether the paste was seen to land, as one line for Diagnostics. */
enum class PasteCheck(val line: String) {
    LANDED("paste check: text appeared"),
    LANDED_ON_RETRY("paste check: first paste didn't land, second did"),
    MISSED_SET_TEXT("paste check: didn't land, used set-text"),
    MISSED_CLIPBOARD("paste check: didn't land, left on the clipboard"),
    UNCHECKED("paste check: couldn't check this field"),
    REFUSED_SET_TEXT("paste check: the field refused the paste, used set-text"),
    REFUSED_CLIPBOARD("paste check: the field refused the paste, left on the clipboard"),
}

/** What became of the user's own clipboard after a dictation, as one line for Diagnostics. */
enum class ClipboardRestore(val line: String) {
    RESTORED("clipboard: put back what was there before"),
    LEFT_UNREADABLE("clipboard: left as is, with the dictation on it (Android doesn't let Wirl read the clipboard, so it can't tell whether it's still the dictation)"),
    SKIPPED_CHANGED("clipboard: not put back, something else was copied after the paste"),
    KEPT_NOT_LANDED("clipboard: not put back, the dictation stays there because it didn't land"),
    ;

    companion object {
        /**
         * Put the old clip back only while the clipboard is known to still hold the dictation. [now] is null when it can't
         * be read (Android 10+ hides the clipboard from apps in the background). Then the old clip couldn't have been
         * read either, so putting it "back" would only empty the clipboard: it's left alone, with the dictation on it.
         */
        fun decide(now: String?, ours: String): ClipboardRestore = when (now) {
            null -> LEFT_UNREADABLE
            ours -> RESTORED
            else -> SKIPPED_CHANGED
        }

        /** Whether [decision] means the old clip is put back. */
        fun putsBack(decision: ClipboardRestore): Boolean = decision == RESTORED
    }
}

data class PasteResult(val method: InsertMethod, val check: PasteCheck)

object DictationPaste {
    /** Wait after setting the clipboard, so the change has reached the system before the app reads it. */
    const val SETTLE_MS = 60L

    /** How often the field is read while waiting for the paste to show up. */
    const val POLL_MS = 60L

    /** How long to wait for a paste to show up before trying again. */
    const val CHECK_MS = 1000L

    /**
     * Inserts [piece] at the cursor by putting it on the clipboard and pasting, then checks it landed:
     * - the field's text changed: done;
     * - unchanged after [CHECK_MS]: paste once more, and if that doesn't show up either, rewrite the whole field with
     *   [fallbackText] (set-text); if that fails too, the dictation stays on the clipboard for pasting by hand;
     * - the field can't be read: unchecked, as before, and never pasted twice, so text can't be typed twice.
     * A field that refuses the paste goes straight to set-text, as before.
     *
     * The user's clipboard is put back a moment later, only while it still holds the dictation, and never when the
     * dictation didn't land. [onRestore] reports what happened to it.
     */
    suspend fun <T> insert(
        clipboard: ClipboardPort<T>,
        field: FieldPort,
        piece: String,
        onRestore: (ClipboardRestore) -> Unit = {},
        sleep: suspend (Long) -> Unit = { delay(it) },
        fallbackText: () -> Pair<String, Int>?,
    ): PasteResult {
        val saved = clipboard.current()
        clipboard.set(piece)
        sleep(SETTLE_MS)
        val before = field.readText()

        val result = if (!field.paste()) {
            fallback(field, fallbackText, PasteCheck.REFUSED_SET_TEXT, PasteCheck.REFUSED_CLIPBOARD)
        } else if (before == null) {
            PasteResult(InsertMethod.PASTE, PasteCheck.UNCHECKED)
        } else {
            when (waitForChange(field, before, sleep)) {
                Change.CHANGED -> PasteResult(InsertMethod.PASTE, PasteCheck.LANDED)
                Change.UNREADABLE -> PasteResult(InsertMethod.PASTE, PasteCheck.UNCHECKED)
                Change.SAME -> retry(field, before, sleep, fallbackText)
            }
        }

        if (result.method == InsertMethod.FAILED) {
            onRestore(ClipboardRestore.KEPT_NOT_LANDED)
        } else {
            clipboard.restoreIfStillOurs(saved, piece, onRestore)
        }
        return result
    }

    private suspend fun retry(
        field: FieldPort,
        before: String,
        sleep: suspend (Long) -> Unit,
        fallbackText: () -> Pair<String, Int>?,
    ): PasteResult {
        if (!field.paste()) return fallback(field, fallbackText, PasteCheck.MISSED_SET_TEXT, PasteCheck.MISSED_CLIPBOARD)
        return when (waitForChange(field, before, sleep)) {
            Change.CHANGED -> PasteResult(InsertMethod.PASTE, PasteCheck.LANDED_ON_RETRY)
            Change.UNREADABLE -> PasteResult(InsertMethod.PASTE, PasteCheck.UNCHECKED)
            Change.SAME -> fallback(field, fallbackText, PasteCheck.MISSED_SET_TEXT, PasteCheck.MISSED_CLIPBOARD)
        }
    }

    private fun fallback(
        field: FieldPort,
        fallbackText: () -> Pair<String, Int>?,
        usedSetText: PasteCheck,
        leftOnClipboard: PasteCheck,
    ): PasteResult {
        val plan = fallbackText() ?: return PasteResult(InsertMethod.FAILED, leftOnClipboard)
        return if (field.setText(plan.first, plan.second)) {
            PasteResult(InsertMethod.SET_TEXT, usedSetText)
        } else {
            PasteResult(InsertMethod.FAILED, leftOnClipboard)
        }
    }

    private enum class Change { CHANGED, SAME, UNREADABLE }

    /** Reads the field every [POLL_MS] for up to [CHECK_MS], until its text differs from [before]. */
    private suspend fun waitForChange(field: FieldPort, before: String, sleep: suspend (Long) -> Unit): Change {
        var waited = 0L
        while (waited < CHECK_MS) {
            sleep(POLL_MS)
            waited += POLL_MS
            val now = field.readText() ?: return Change.UNREADABLE
            if (now != before) return Change.CHANGED
        }
        return Change.SAME
    }
}
