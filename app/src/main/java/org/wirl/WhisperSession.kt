package org.wirl

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.max

/**
 * The outcome of one Whisper dictation: the text, and what happened to each chunk, for Diagnostics. [duringRecording] is how
 * many chunks had already been transcribed when the user tapped stop. Only the rest were left for the stop.
 */
data class WhisperOutcome(
    val text: String,
    val chunks: List<ChunkResult>,
    val missing: List<Int>,
    val finished: Boolean,
    val stopToTextMs: Long,
    val duringRecording: Int,
)

/**
 * One dictation with on-device Whisper. The microphone is recorded continuously until [stop]. Each pause ends a chunk,
 * and that chunk is transcribed in the background while recording goes on. No audio is written to disk: a chunk's samples
 * are dropped once it's transcribed.
 */
class WhisperSession(
    models: ModelHolder,
    language: String,
    prompt: String?,
    private val now: () -> Long,
) {
    // No live preview: the bubble alone shows recording. Chunks are transcribed as they're cut, in the background.
    private val pipeline = ChunkPipeline(
        model = { models.get() },
        language = language,
        prompt = prompt,
        now = now,
        onResult = {},
    )
    @Volatile private var record: AudioRecord? = null
    private var reader: Thread? = null

    /** Starts recording. Returns false if the microphone can't be opened. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(PauseDetector.SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) return false
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            PauseDetector.SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            max(minBuffer, PauseDetector.SAMPLES_PER_FRAME * 2 * 4),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        rec.startRecording()
        record = rec
        reader = Thread({ readLoop(rec) }, "wirl-record").also { it.start() }
        return true
    }

    /**
     * Stops recording and waits for the last chunks to be transcribed, on a background thread. [onDone] runs on that thread.
     * [tappedAt] is when the user tapped stop, for the timing in Diagnostics.
     */
    fun stop(tappedAt: Long, onDone: (WhisperOutcome) -> Unit) {
        // Read now, on the caller's thread: these are the chunks done before the user tapped stop.
        val duringRecording = pipeline.completedCount()
        Thread({
            val rec = record
            record = null
            // The reader sends its last chunk before it exits, so wait for it before the queue is closed.
            reader?.join(READER_TIMEOUT_MS)
            if (rec != null) {
                runCatching { rec.stop() }
                rec.release()
            }
            val finished = pipeline.finish(FINISH_TIMEOUT_MS)
            onDone(
                WhisperOutcome(
                    text = pipeline.text(),
                    chunks = pipeline.results(),
                    missing = pipeline.missing(),
                    finished = finished,
                    stopToTextMs = now() - tappedAt,
                    duringRecording = duringRecording,
                ),
            )
        }, "wirl-stop").start()
    }

    private fun readLoop(rec: AudioRecord) {
        val frame = ShortArray(PauseDetector.SAMPLES_PER_FRAME)
        val detector = PauseDetector()
        // Room for a full chunk: the detector ends one at the latest after its maximum length, plus the frame that ends it.
        val chunk = FloatArray(MAX_CHUNK_SAMPLES)
        var size = 0
        while (record === rec) {
            val read = rec.read(frame, 0, frame.size)
            if (read < 0) {
                Log.e(TAG, "Microphone read failed: $read")
                break
            }
            if (read == 0) continue
            for (i in 0 until read) chunk[size + i] = frame[i] / 32768f
            val loudness = PauseDetector.loudness(chunk, size, size + read)
            size += read
            if (detector.onFrame(loudness)) {
                size = endChunk(chunk, size, detector)
            }
        }
        // Whatever is left when recording stops is the last chunk.
        if (size > 0) endChunk(chunk, size, detector)
    }

    /** Sends the current chunk for transcription if it had speech. Returns the new size, which is 0. */
    private fun endChunk(chunk: FloatArray, size: Int, detector: PauseDetector): Int {
        val hadSpeech = detector.reset()
        if (hadSpeech) pipeline.submit(chunk.copyOf(size))
        return 0
    }

    private companion object {
        const val TAG = "Wirl"
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val READER_TIMEOUT_MS = 2_000L
        /** A dictation can end with a long chunk still being transcribed. Give it this long before reporting it unfinished. */
        const val FINISH_TIMEOUT_MS = 60_000L
        /** The longest chunk, plus the frame that ends it. */
        const val MAX_CHUNK_SAMPLES = (20_000 / PauseDetector.FRAME_MS + 2) * PauseDetector.SAMPLES_PER_FRAME
    }
}
