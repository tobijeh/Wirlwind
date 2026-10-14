package org.wirl

import java.io.File
import java.io.IOException

/** Turns one chunk of 16 kHz mono audio into text. */
interface SpeechTranscriber : AutoCloseable {
    /** The text for [pcm], or an exception if transcription failed. [prompt] is passed to the model as its initial prompt. */
    fun transcribe(pcm: FloatArray, language: String, prompt: String?): String
}

/**
 * Whisper through whisper.cpp. A model can be freed only between transcriptions: [close] waits for one in progress.
 */
class WhisperTranscriber private constructor(private var handle: Long, private val threads: Int) : SpeechTranscriber {
    @Synchronized
    override fun transcribe(pcm: FloatArray, language: String, prompt: String?): String {
        check(handle != 0L) { "The model isn't loaded" }
        return WhisperNative.nativeTranscribe(handle, pcm, language, prompt, threads)
            ?: throw IOException("Whisper couldn't transcribe this audio")
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) WhisperNative.nativeFree(handle)
        handle = 0L
    }

    companion object {
        /** Loads [file] into memory. Returns null if it can't be loaded, which usually means the file is damaged. */
        fun load(file: File, threads: Int): WhisperTranscriber? {
            val handle = WhisperNative.nativeInit(file.path)
            return if (handle == 0L) null else WhisperTranscriber(handle, threads)
        }
    }
}

/**
 * Keeps the model loaded while it's in use, and frees it after [idleMs] with no use. The next use loads it again.
 * [schedule] runs a task after a delay on the main thread.
 */
class ModelHolder(
    private val load: () -> SpeechTranscriber?,
    private val now: () -> Long,
    private val schedule: (delayMs: Long, task: Runnable) -> Unit,
    private val idleMs: Long = IDLE_MS,
) {
    private var loaded: SpeechTranscriber? = null
    private var lastUse = 0L

    val isLoaded: Boolean @Synchronized get() = loaded != null

    /** The loaded model, loading it first if needed. Null if it can't be loaded. */
    @Synchronized
    fun get(): SpeechTranscriber? {
        if (loaded == null) loaded = load()
        lastUse = now()
        schedule(idleMs) { checkIdle() }
        return loaded
    }

    /** Frees the model if it hasn't been used for [idleMs]. */
    @Synchronized
    fun checkIdle() {
        if (loaded != null && now() - lastUse >= idleMs) unload()
    }

    @Synchronized
    fun unload() {
        loaded?.close()
        loaded = null
    }

    companion object {
        /** Five minutes without dictation, then the model is freed. */
        const val IDLE_MS = 5 * 60 * 1000L
    }
}
