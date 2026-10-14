package org.wirl

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Captures the microphone once for the whole dictation, so no audio is lost between recognizer sessions. Audio is kept
 * in a rolling window. Each recognizer session is fed through a pipe: first the audio it missed, then live audio.
 */
class OwnAudio {
    private val lock = Any()
    private val ring = PcmRing(WINDOW_SAMPLES)
    @Volatile private var record: AudioRecord? = null
    private var reader: Thread? = null
    private var pipe: LivePipe? = null
    private var droppedSamples = 0L

    val isRunning: Boolean get() = record != null

    /** The oldest audio still held, in samples. */
    val oldest: Long get() = synchronized(lock) { ring.oldest }

    /** Starts capturing. Returns false if the microphone can't be opened. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (record != null) return true
        val minBuffer = AudioRecord.getMinBufferSize(AudioFormat16k.SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) return false
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            AudioFormat16k.SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            maxOf(minBuffer, CHUNK_BYTES * 4),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        rec.startRecording()
        record = rec
        reader = Thread({ readLoop(rec) }, "wirl-audio").also { it.start() }
        return true
    }

    /** Stops capturing and closes any open pipe. */
    fun stop() {
        val rec = record ?: return
        record = null
        reader?.join(1000)
        reader = null
        runCatching { rec.stop() }
        rec.release()
        synchronized(lock) {
            pipe?.close()
            pipe = null
        }
    }

    /**
     * Ends the current recognizer pipe, if any. Returns the sample where the audio it carried ended. The next pipe
     * replays from before that point, so nothing spoken across the gap is lost.
     */
    fun closePipe(): Long = synchronized(lock) {
        pipe?.close()
        pipe = null
        ring.end
    }

    /**
     * Opens a pipe for a new recognizer session. It first carries held audio from [fromSample] on, then live audio.
     * Returns the read side for the recognizer, or null if the pipe couldn't be made.
     */
    fun openPipe(fromSample: Long): ParcelFileDescriptor? = synchronized(lock) {
        pipe?.close()
        val replay = ring.sliceFrom(fromSample) ?: ring.sliceFrom(ring.oldest) ?: ByteArray(0)
        val fds = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull() ?: return null
        val live = LivePipe(fds[1], replay)
        pipe = live
        live.start()
        fds[0]
    }

    /** Samples dropped since the last call, because a recognizer fell behind the live audio. */
    fun takeDroppedSamples(): Long = synchronized(lock) {
        val dropped = droppedSamples
        droppedSamples = 0
        dropped
    }

    private fun readLoop(rec: AudioRecord) {
        val buf = ByteArray(CHUNK_BYTES)
        while (record === rec) {
            val n = rec.read(buf, 0, buf.size)
            if (n < 0) {
                Log.e(TAG, "AudioRecord read failed: $n")
                break
            }
            if (n > 0) {
                synchronized(lock) {
                    ring.append(buf, n)
                    pipe?.offer(buf.copyOf(n), n)?.let { droppedSamples += it }
                }
            }
        }
    }

    /** Writes one recognizer session's audio: first the replay, then live chunks, until closed. */
    private inner class LivePipe(private val writeSide: ParcelFileDescriptor, private val replay: ByteArray) {
        private val queue = LinkedBlockingQueue<ByteArray>(MAX_QUEUED_CHUNKS)
        @Volatile private var closing = false
        private val writer = Thread({ write() }, "wirl-audio-pipe")

        fun start() = writer.start()

        /** Queues a live chunk. Returns how many samples were dropped if the recognizer fell too far behind. */
        fun offer(chunk: ByteArray, length: Int): Long? =
            if (queue.offer(chunk)) null else (length / AudioFormat16k.BYTES_PER_SAMPLE).toLong()

        /** Stops taking new audio. The writer sends what's already queued, then closes the pipe. */
        fun close() {
            closing = true
        }

        private fun write() {
            val out = ParcelFileDescriptor.AutoCloseOutputStream(writeSide)
            try {
                out.write(replay)
                while (true) {
                    val chunk = queue.poll(50, TimeUnit.MILLISECONDS)
                    if (chunk != null) out.write(chunk) else if (closing) break
                }
            } catch (e: IOException) {
                // The recognizer closed its side, which is normal when its session ends.
                Log.d(TAG, "audio pipe closed: ${e.message}")
            } finally {
                runCatching { out.close() }
            }
        }
    }

    private companion object {
        const val TAG = "Wirl"
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        /** Five seconds of audio is held for replay. */
        const val WINDOW_SAMPLES = 5 * AudioFormat16k.SAMPLE_RATE
        /** 20 ms per read. */
        const val CHUNK_BYTES = 640
        /** About five seconds of queued live audio before a recognizer that isn't reading counts as a gap. */
        const val MAX_QUEUED_CHUNKS = 250
    }
}
