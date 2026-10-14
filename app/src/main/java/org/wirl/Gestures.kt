package org.wirl

/** Identifies a text field closely enough to tell whether it's still the same one. */
data class FieldKey(val packageName: String, val viewId: String, val left: Int, val top: Int)

/** The last successful cleanup in a field: what it was before, and what it became. */
data class CleanupRecord(val field: FieldKey, val original: String, val cleaned: String)

sealed interface GestureAction {
    data object StartRecording : GestureAction
    data object StopRecording : GestureAction
    data object Clean : GestureAction
    /** Put back [original]. */
    data class Undo(val original: String) : GestureAction
    data object Ignore : GestureAction
}

/**
 * Decides what a tap or a hold on the bubble should do.
 * Tap: start dictating, or stop it. Hold: clean up the field, or undo the last cleanup if the text is unchanged.
 */
class GestureMachine {
    enum class Mode { IDLE, RECORDING, PROCESSING }

    var mode: Mode = Mode.IDLE
        private set

    private var lastCleanup: CleanupRecord? = null

    fun onTap(): GestureAction = when (mode) {
        Mode.IDLE -> {
            mode = Mode.RECORDING
            GestureAction.StartRecording
        }
        Mode.RECORDING -> {
            mode = Mode.PROCESSING
            GestureAction.StopRecording
        }
        Mode.PROCESSING -> GestureAction.Ignore
    }

    /**
     * A hold on the bubble. [field] is the focused field, or null if there isn't one.
     * [currentText] is what the field holds right now.
     */
    fun onHold(field: FieldKey?, currentText: String): GestureAction {
        if (mode != Mode.IDLE || field == null) return GestureAction.Ignore
        val record = lastCleanup
        if (record != null && record.field != field) lastCleanup = null
        val current = lastCleanup
        if (current != null && currentText == current.cleaned) {
            // Undo only applies if nothing has been edited since the cleanup.
            lastCleanup = null
            return GestureAction.Undo(current.original)
        }
        mode = Mode.PROCESSING
        return GestureAction.Clean
    }

    /** A cleanup succeeded; remember it so a hold can undo it. */
    fun onCleaned(field: FieldKey, original: String, cleaned: String) {
        lastCleanup = CleanupRecord(field, original, cleaned)
    }

    /** A cleanup or dictation has finished, successfully or not. */
    fun finish() {
        mode = Mode.IDLE
    }
}

sealed interface RecognizerEvent {
    data class Partial(val text: String) : RecognizerEvent
    data class Results(val text: String) : RecognizerEvent
    data class Error(val code: Int) : RecognizerEvent
}

sealed interface SessionAction {
    /** Listen again: the recognizer stopped on a pause while dictation is still on. */
    data object Restart : SessionAction
    data class Finish(val transcript: String, val error: DictationError?, val errorCode: Int? = null) : SessionAction
}

/**
 * One dictation, from tap to tap. Recognizers stop on pauses, so this restarts them and keeps appending
 * the text until the user stops.
 */
class DictationSession {
    private val assembler = TranscriptAssembler()
    private var listening = false
    private var stopRequested = false

    /** Text so far, including any partial result still being spoken. */
    val transcript: String get() = assembler.text()

    fun onEvent(event: RecognizerEvent): SessionAction? = when (event) {
        is RecognizerEvent.Partial -> {
            assembler.partial(event.text)
            null
        }
        is RecognizerEvent.Results -> {
            assembler.commit(event.text)
            listening = false
            if (stopRequested) finish(null) else restart()
        }
        is RecognizerEvent.Error -> {
            listening = false
            val error = DictationError.from(event.code)
            when {
                // Silence while dictating is normal: keep going unless the user stopped or nothing was said yet.
                error == DictationError.NO_SPEECH && assembler.text().isNotEmpty() && !stopRequested -> restart()
                error == DictationError.NO_SPEECH && stopRequested -> finish(null)
                else -> finish(error, event.code)
            }
        }
    }

    /** The user tapped to stop. Returns the transcript straight away if the recognizer isn't running. */
    fun stopByUser(): SessionAction? {
        stopRequested = true
        if (listening) return null
        return finish(null)
    }

    private fun restart(): SessionAction {
        // Not listening until the service confirms the recognizer has started again (see onListening).
        listening = false
        return SessionAction.Restart
    }

    private fun finish(error: DictationError?, code: Int? = null): SessionAction.Finish {
        listening = false
        // Keep whatever was heard, including a partial result that never became final.
        return SessionAction.Finish(assembler.text(), error, code)
    }

    /** The recognizer has started listening (first start, or after a restart). */
    fun onListening() {
        listening = true
    }
}

/** Joins recognized text across recognizer restarts without repeating or dropping words. */
class TranscriptAssembler {
    private val committed = mutableListOf<String>()
    private var partial = ""

    fun partial(text: String) {
        partial = text.trim()
    }

    fun commit(text: String) {
        val clean = text.trim()
        if (clean.isNotEmpty()) committed += clean
        partial = ""
    }

    fun text(): String {
        val parts = if (partial.isEmpty()) committed else committed + partial
        return parts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }
}

enum class DictationPlacement {
    IN_FIELD,
    CLIPBOARD;

    companion object {
        /** Dictated text goes into the field it started in, if that field can still be edited. Otherwise the clipboard. */
        fun decide(fieldFound: Boolean, sameField: Boolean, editable: Boolean): DictationPlacement =
            if (fieldFound && sameField && editable) IN_FIELD else CLIPBOARD
    }
}

object DictationResult {
    /**
     * The text to insert. With AI cleanup on, the cleaned text if it arrived, otherwise the raw transcript
     * so nothing said is lost. With cleanup off, always the raw transcript.
     */
    fun choose(raw: String, aiEnabled: Boolean, cleaned: String?): String {
        if (!aiEnabled) return raw
        return cleaned?.takeIf { it.isNotBlank() } ?: raw
    }
}

data class Insertion(val text: String, val cursor: Int)

object TextInsertion {
    /**
     * Puts [inserted] at the selection (or the cursor when nothing is selected), keeping the rest of the field.
     * Adds a space before or after when the words would otherwise run together. The cursor ends after the new text.
     */
    fun plan(existing: String, selectionStart: Int, selectionEnd: Int, inserted: String): Insertion {
        val len = existing.length
        // A selection made backwards is still a selection.
        val (lo, hi) = if (selectionStart <= selectionEnd) selectionStart to selectionEnd else selectionEnd to selectionStart
        val start = if (lo in 0..len) lo else len
        val end = if (hi in start..len) hi else start
        val text = inserted.trim()
        if (text.isEmpty()) return Insertion(existing, start)

        val before = existing.substring(0, start)
        val after = existing.substring(end)
        val spaceBefore = before.isNotEmpty() && !before.last().isWhitespace()
        val spaceAfter = after.isNotEmpty() && after.first().let { it.isLetterOrDigit() }
        val middle = (if (spaceBefore) " " else "") + text + (if (spaceAfter) " " else "")
        val result = before + middle + after
        val cursor = before.length + middle.length - (if (spaceAfter) 1 else 0)
        return Insertion(result, cursor)
    }
}
