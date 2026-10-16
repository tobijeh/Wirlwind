package org.wirl

import java.io.IOException
import java.util.TreeMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One chunk's transcription. [text] is empty and [error] set if it failed. */
data class ChunkResult(
    val seq: Int,
    val text: String,
    val error: String?,
    val transcribeMs: Long,
    val samples: Int,
    /** Set when the model's text was dropped as a sound annotation or silence, so nothing is inserted for it. */
    val dropReason: String? = null,
)

/**
 * Joins chunk texts in the order they were spoken. Each chunk number counts once: a result that arrives twice is ignored,
 * and [missing] lists any chunk number that never came back.
 */
class ChunkAssembler {
    private val texts = TreeMap<Int, String>()
    var duplicates = 0
        private set

    /** Records chunk [seq]. Returns false if that number was already recorded. */
    fun add(seq: Int, text: String): Boolean {
        if (texts.containsKey(seq)) {
            duplicates++
            return false
        }
        texts[seq] = text.trim()
        return true
    }

    /** The whole text so far, in order, with single spaces. */
    fun text(): String = texts.values.filter { it.isNotEmpty() }.joinToString(" ").replace(Regex("\\s+"), " ").trim()

    /** Chunk numbers below the highest one received that never arrived. */
    fun missing(): List<Int> {
        if (texts.isEmpty()) return emptyList()
        return (0..texts.lastKey()).filter { !texts.containsKey(it) }
    }
}

/**
 * Transcribes chunks one at a time, in the order they were submitted, on a background thread. Recording never waits for
 * it. Each chunk's audio is dropped once it's transcribed. [onResult] runs on the background thread.
 */
class ChunkPipeline(
    private val model: () -> SpeechTranscriber?,
    private val language: String,
    private val prompt: String?,
    private val now: () -> Long,
    private val onResult: (ChunkResult) -> Unit,
) {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "wirl-whisper") }
    private val lock = Any()
    private val assembler = ChunkAssembler()
    private val results = mutableListOf<ChunkResult>()
    private var nextSeq = 0

    /** Chunks queued so far. */
    fun submittedCount(): Int = nextSeq

    /** Chunks transcribed so far. Chunks finished during recording stay done; stop doesn't redo them. */
    fun completedCount(): Int = synchronized(lock) { results.size }

    /** Queues one chunk of 16 kHz mono samples. Called from the recording thread. */
    fun submit(pcm: FloatArray) {
        val seq = nextSeq++
        worker.execute { run(seq, pcm) }
    }

    private fun run(seq: Int, pcm: FloatArray) {
        val started = now()
        val result = try {
            val transcriber = model() ?: throw IOException("The Whisper model isn't loaded")
            val raw = transcriber.transcribe(pcm, language, prompt)
            val spoken = TranscriptFilter.clean(raw)
            val dropped = if (raw.isNotBlank() && spoken.isBlank()) "sound or silence only" else null
            ChunkResult(seq, spoken, null, now() - started, pcm.size, dropped)
        } catch (e: Throwable) {
            ChunkResult(seq, "", e.message ?: e.javaClass.simpleName, now() - started, pcm.size)
        }
        synchronized(lock) {
            assembler.add(seq, result.text)
            results += result
        }
        onResult(result)
    }

    /** Stops taking chunks and waits for the queued ones to finish. Returns false if they didn't finish in time. */
    fun finish(timeoutMs: Long): Boolean {
        worker.shutdown()
        return worker.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** The text so far, in spoken order. */
    fun text(): String = synchronized(lock) { assembler.text() }

    /** Every chunk's result, in spoken order. */
    fun results(): List<ChunkResult> = synchronized(lock) { results.sortedBy { it.seq } }

    fun missing(): List<Int> = synchronized(lock) { assembler.missing() }
}
