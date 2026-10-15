package org.wirl

/** Timing of one Claude request. */
data class ClaudeCallTiming(
    /** From sending the request to the first byte of the reply. */
    val firstByteMs: Long,
    val totalMs: Long,
    /** False when this request had to open a new connection (TLS handshake included). */
    val connectionReused: Boolean,
    val outputTokens: Int?,
    /** Whether the reply used extended thinking. Wirl doesn't request thinking, so this is normally false. */
    val thinkingUsed: Boolean,
)

/** Timing of one Whisper dictation. */
data class WhisperTiming(
    /** Time waited for the model to be ready. Near zero when the model was already loaded. */
    val modelWaitMs: Long,
    /** How long the chunk cut at stop took to transcribe. */
    val finalChunkMs: Long?,
    /** From the tap to stop until every chunk was transcribed. */
    val totalMs: Long,
    val chunks: Int,
    val duringRecording: Int,
)

/** Every stage of one dictation, in the order it happens. Stages that didn't run are left out of the lines. */
data class DictationTiming(
    val whisper: WhisperTiming? = null,
    val claude: ClaudeCallTiming? = null,
    val insertMs: Long? = null,
    val stopToInsertMs: Long? = null,
) {
    fun lines(): List<String> = buildList {
        whisper?.let {
            add("whisper model wait: ${it.modelWaitMs} ms")
            add("whisper final chunk: ${it.finalChunkMs?.let { ms -> "$ms ms" } ?: "none"}")
            add("whisper total: ${it.totalMs} ms, ${it.chunks} chunks (${it.duringRecording} during recording)")
        }
        claude?.let {
            add("claude first byte: ${it.firstByteMs} ms, total: ${it.totalMs} ms, connection: ${if (it.connectionReused) "reused" else "new"}")
            add("claude output tokens: ${it.outputTokens ?: "unknown"}, thinking: ${if (it.thinkingUsed) "used" else "not used"}")
        }
        insertMs?.let { add("insert: $it ms") }
        stopToInsertMs?.let { add("stop to insert: $it ms") }
    }
}
