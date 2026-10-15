package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class WhisperPreviewTest {
    @Test fun whisperShowsNoPreviewAndGoogleKeepsItsWordByWordPreview() {
        assertEquals(LivePreview.NONE, DictationPreview.forEngine(DictationEngine.WHISPER))
        assertEquals(LivePreview.WORDS, DictationPreview.forEngine(DictationEngine.GOOGLE))
    }

    /** Counts transcriptions, and answers with the chunk's length so the test can tell chunks apart. */
    private class CountingTranscriber : SpeechTranscriber {
        val calls = CopyOnWriteArrayList<Int>()
        override fun transcribe(pcm: FloatArray, language: String, prompt: String?): String {
            calls += pcm.size
            return "w${pcm.size}"
        }
        override fun close() = Unit
    }

    private fun awaitCompleted(pipeline: ChunkPipeline, count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (pipeline.completedCount() < count && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals("chunks finished during recording", count, pipeline.completedCount())
    }

    @Test fun chunksTranscribedDuringRecordingAreReusedAtStopNotTranscribedAgain() {
        val transcriber = CountingTranscriber()
        val pipeline = ChunkPipeline(
            model = { transcriber },
            language = "en",
            prompt = null,
            now = { 0L },
            onResult = {},
        )
        // Two pauses while recording: each chunk is transcribed in the background as it's cut.
        pipeline.submit(FloatArray(10))
        pipeline.submit(FloatArray(20))
        awaitCompleted(pipeline, 2)
        val duringRecording = pipeline.completedCount()

        // The user taps stop. Only the final chunk is new.
        pipeline.submit(FloatArray(30))
        assertTrue(pipeline.finish(5_000))

        assertEquals("the two earlier chunks were finished during recording", 2, duringRecording)
        assertEquals("each chunk is transcribed exactly once", listOf(10, 20, 30), transcriber.calls)
        assertEquals("w10 w20 w30", pipeline.text())
        assertEquals(listOf(0, 1, 2), pipeline.results().map { it.seq })
    }

    @Test fun chunksAreCountedAsSubmittedAndCompleted() {
        val pipeline = ChunkPipeline(
            model = { CountingTranscriber() },
            language = "en",
            prompt = null,
            now = { 0L },
            onResult = {},
        )
        pipeline.submit(FloatArray(5))
        assertEquals(1, pipeline.submittedCount())
        assertTrue(pipeline.finish(5_000))
        assertEquals(1, pipeline.completedCount())
    }
}
