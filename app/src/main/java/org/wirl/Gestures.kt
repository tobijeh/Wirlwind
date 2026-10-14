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
    /** [alternatives] are the other transcriptions for this result, most likely first. */
    data class Results(val text: String, val alternatives: List<String> = emptyList()) : RecognizerEvent
    data class Error(val code: Int) : RecognizerEvent
    /** One finished segment of a segmented session, most likely text first. */
    data class Segment(val text: String, val alternatives: List<String> = emptyList()) : RecognizerEvent
    /** The segmented session has ended. Nothing more will come. */
    data object EndOfSegments : RecognizerEvent
}

sealed interface SessionAction {
    /** Listen again: the recognizer stopped on a pause while dictation is still on. */
    data object Restart : SessionAction
    data class Finish(
        val transcript: String,
        val error: DictationError?,
        val errorCode: Int? = null,
        /** Whole-dictation alternatives to offer the cleanup, most likely first. */
        val alternatives: List<String> = emptyList(),
    ) : SessionAction
}

/**
 * One dictation, from tap to tap. Three ways the recognizer hears the speech:
 * - [Mode.SEGMENTED]: one open session that reports each segment (Android 13+).
 * - [Mode.RESTART_OWN_AUDIO]: restarts, fed from our own microphone capture, so audio spoken between sessions is
 *   replayed rather than lost (Android 13+, when segmented sessions aren't supported).
 * - [Mode.RESTART_MIC]: restarts, with the recognizer using the microphone itself (older versions).
 */
class DictationSession(segmented: Boolean = false, ownAudio: Boolean = false) {
    enum class Mode(val label: String) {
        SEGMENTED("segmented"),
        RESTART_OWN_AUDIO("restart with own audio"),
        RESTART_MIC("restart with mic"),
    }

    private val assembler = TranscriptAssembler()
    private val ownAudioAllowed = ownAudio
    private var listening = false
    private var stopRequested = false
    private var fellBack = false
    private var replaying = false
    private var note = ""
    private var segments = 0
    private var restarts = 0
    private var gaps = 0
    private var gapMs = 0L

    var mode: Mode = when {
        segmented -> Mode.SEGMENTED
        ownAudio -> Mode.RESTART_OWN_AUDIO
        else -> Mode.RESTART_MIC
    }
        private set

    /** Text so far, including any partial result still being spoken. */
    val transcript: String get() = assembler.text()

    /** True once the user has tapped to stop, so the next pause ends the dictation rather than restarting it. */
    val stopping: Boolean get() = stopRequested

    fun onEvent(event: RecognizerEvent): SessionAction? = when (event) {
        is RecognizerEvent.Partial -> {
            assembler.partial(event.text)
            null
        }
        is RecognizerEvent.Segment -> {
            if (assembler.commitSegment(event.text, event.alternatives)) segments++
            null
        }
        RecognizerEvent.EndOfSegments -> {
            listening = false
            finish(null)
        }
        is RecognizerEvent.Results -> {
            // In a segmented session the segments already hold this text. With none received, this recognizer
            // doesn't do segments, so the session falls back to restarts and takes the result as usual.
            if (mode == Mode.SEGMENTED && segments > 0) {
                null
            } else {
                if (mode == Mode.SEGMENTED) fallBackToRestarts()
                if (replaying) assembler.commitReplayed(event.text, event.alternatives) else assembler.commit(event.text, event.alternatives)
                replaying = false
                segments++
                listening = false
                if (stopRequested) finish(null) else restart()
            }
        }
        is RecognizerEvent.Error -> {
            listening = false
            val error = DictationError.from(event.code)
            when {
                // A segmented session that fails before any text came: this device doesn't support it. Use restarts.
                mode == Mode.SEGMENTED && segments == 0 && assembler.text().isEmpty() && !stopRequested -> {
                    fallBackToRestarts()
                    SessionAction.Restart
                }
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
        restarts++
        return SessionAction.Restart
    }

    private fun fallBackToRestarts() {
        mode = if (ownAudioAllowed) Mode.RESTART_OWN_AUDIO else Mode.RESTART_MIC
        fellBack = true
    }

    /** Our own audio couldn't be opened, so the recognizer uses the microphone for the rest of this dictation. */
    fun useMicrophone(reason: String) {
        mode = Mode.RESTART_MIC
        note = reason
    }

    /** The next session replays audio from before it started, so its first result may repeat the end of the text. */
    fun beginReplay() {
        replaying = true
    }

    /** Audio that was wanted for a replay but had already gone. */
    fun noteGap(ms: Long) {
        if (ms <= 0) return
        gaps++
        gapMs = maxOf(gapMs, ms)
    }

    private fun finish(error: DictationError?, code: Int? = null): SessionAction.Finish {
        listening = false
        // Keep whatever was heard, including a partial result that never became final.
        return SessionAction.Finish(assembler.text(), error, code, assembler.alternatives())
    }

    /** What Diagnostics shows: the mode that ran, and how many segments, restarts, and audio gaps there were. */
    fun summary(): String {
        val fell = if (fellBack) " (segmented unsupported, fell back)" else ""
        val why = if (note.isNotEmpty()) " ($note)" else ""
        val gapText = if (gaps == 0) "none" else "$gaps (longest $gapMs ms)"
        return "mode: ${mode.label}$fell$why, segments: $segments, restarts: $restarts, audio gaps: $gapText"
    }

    /** The recognizer has started listening (first start, or after a restart). */
    fun onListening() {
        listening = true
    }
}

/** Joins recognized text across recognizer restarts without repeating or dropping words. */
class TranscriptAssembler {
    /** One committed result: its top transcript and the alternatives the recognizer offered for it. */
    private data class Segment(val top: String, val alternatives: List<String>)

    private val committed = mutableListOf<Segment>()

    private companion object {
        /** How many words at the start of a replayed session can repeat the end of the text so far. */
        const val MAX_OVERLAP_WORDS = 4
    }
    private var partial = ""

    fun partial(text: String) {
        partial = text.trim()
    }

    fun commit(text: String, alternatives: List<String> = emptyList()) {
        val clean = text.trim()
        if (clean.isNotEmpty()) committed += Segment(clean, alternatives.map { it.trim() }.filter { it.isNotEmpty() })
        partial = ""
    }

    fun text(): String {
        val parts = committed.map { it.top } + listOfNotNull(partial.takeIf { it.isNotEmpty() })
        return parts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Adds text from a session that replayed audio from before it started. Words at its start that repeat the end of
     * the text so far are dropped, so the replayed overlap isn't heard twice.
     */
    fun commitReplayed(text: String, alternatives: List<String> = emptyList()) {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val before = committed.map { it.top }.joinToString(" ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        fun norm(word: String) = word.lowercase().trim { !it.isLetterOrDigit() }
        val overlap = (minOf(MAX_OVERLAP_WORDS, before.size, words.size) downTo 1).firstOrNull { k ->
            before.takeLast(k).map(::norm) == words.take(k).map(::norm)
        } ?: 0
        commit(words.drop(overlap).joinToString(" "), alternatives)
    }

    /**
     * Adds a finished segment, in order. A segment identical to the one before it is a repeat and is skipped.
     * Returns whether it was added.
     */
    fun commitSegment(text: String, alternatives: List<String> = emptyList()): Boolean {
        val clean = text.trim()
        partial = ""
        if (clean.isEmpty() || committed.lastOrNull()?.top == clean) return false
        committed += Segment(clean, alternatives.map { it.trim() }.filter { it.isNotEmpty() })
        return true
    }

    /**
     * Whole-dictation alternatives: the k-th alternative of each result, joined in order. Results without a k-th
     * alternative use their top transcript. Distinct, different from [text], and at most [max] of them.
     */
    fun alternatives(max: Int = Alternatives.MAX - 1): List<String> {
        val top = text()
        val partialTail = partial.takeIf { it.isNotEmpty() }
        return (0 until max)
            .map { k ->
                val joined = committed.map { it.alternatives.getOrNull(k) ?: it.top }.toMutableList()
                partialTail?.let { joined += it }
                joined.joinToString(" ").replace(Regex("\\s+"), " ").trim()
            }
            .filter { it.isNotEmpty() && it != top }
            .distinct()
            .take(max)
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
     * The exact text to paste at the cursor (replacing any selection): [inserted], with a space before or after where
     * the words would otherwise run together. Pasting this keeps the rest of the field untouched.
     */
    fun pieceFor(
        existing: String,
        selectionStart: Int,
        selectionEnd: Int,
        inserted: String,
        allowLeadingSpace: Boolean = true,
    ): String {
        val len = existing.length
        val (lo, hi) = if (selectionStart <= selectionEnd) selectionStart to selectionEnd else selectionEnd to selectionStart
        val start = if (lo in 0..len) lo else len
        val end = if (hi in start..len) hi else start
        val text = inserted.trim()
        if (text.isEmpty()) return ""
        val before = existing.substring(0, start)
        val after = existing.substring(end)
        // A space goes before only when the caller allows it and there's text before the cursor.
        val spaceBefore = allowLeadingSpace && before.isNotEmpty() && !before.last().isWhitespace()
        val spaceAfter = after.isNotEmpty() && after.first().isLetterOrDigit()
        return (if (spaceBefore) " " else "") + text + (if (spaceAfter) " " else "")
    }

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
