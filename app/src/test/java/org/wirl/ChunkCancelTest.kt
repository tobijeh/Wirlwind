package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class ChunkCancelTest {
    /** Records every chunk it's asked for. The first call waits on [gate], so a test can cancel while it's busy. */
    private class RecordingTranscriber(private val gate: CountDownLatch? = null) : SpeechTranscriber {
        val calls = CopyOnWriteArrayList<Int>()
        /** Counted down when the first chunk has entered the model. */
        val entered = CountDownLatch(1)
        override fun transcribe(pcm: FloatArray, language: String, prompt: String?): String {
            calls += pcm.size
            if (calls.size == 1) {
                entered.countDown()
                gate?.await()
            }
            return "w${pcm.size}"
        }
        override fun close() = Unit
    }

    private fun pipeline(transcriber: SpeechTranscriber) = ChunkPipeline(
        model = { transcriber },
        language = "en",
        prompt = null,
        now = { 0L },
        onResult = {},
    )

    @Test fun cancelledChunksQueuedBehindABusyOneAreNotTranscribed() {
        val gate = CountDownLatch(1)
        val transcriber = RecordingTranscriber(gate)
        val pipeline = pipeline(transcriber)
        pipeline.submit(FloatArray(10))
        pipeline.submit(FloatArray(20))
        pipeline.submit(FloatArray(30))
        // Cancel only once the first chunk is inside the model, so the test doesn't race the worker.
        assertTrue(transcriber.entered.await(5, TimeUnit.SECONDS))
        pipeline.cancel()
        gate.countDown()
        assertTrue(pipeline.finish(5_000))
        assertEquals("only the chunk already in the model ran", listOf(10), transcriber.calls.toList())
    }

    @Test fun chunksSubmittedAfterCancelAreIgnored() {
        val transcriber = RecordingTranscriber()
        val pipeline = pipeline(transcriber)
        pipeline.cancel()
        pipeline.submit(FloatArray(10))
        assertTrue(pipeline.finish(5_000))
        assertEquals(emptyList<Int>(), transcriber.calls.toList())
        assertEquals("nothing is assembled after a cancel", "", pipeline.text())
    }

    @Test fun aPipelineThatWasNotCancelledStillTranscribesEverything() {
        val transcriber = RecordingTranscriber()
        val pipeline = pipeline(transcriber)
        pipeline.submit(FloatArray(10))
        pipeline.submit(FloatArray(20))
        assertTrue(pipeline.finish(5_000))
        assertEquals(listOf(10, 20), transcriber.calls.toList())
    }
}
