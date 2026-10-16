package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ChunkPipelineTest {
    /** Answers with the chunk's length, so a test can tell which chunk was transcribed. */
    private class LengthTranscriber(private val failOn: Int? = null) : SpeechTranscriber {
        val prompts = CopyOnWriteArrayList<String?>()
        val languages = CopyOnWriteArrayList<String>()
        override fun transcribe(pcm: FloatArray, language: String, prompt: String?): String {
            prompts += prompt
            languages += language
            if (pcm.size == failOn) throw IllegalStateException("bad audio")
            return "w${pcm.size}"
        }
        override fun close() = Unit
    }

    private fun pipeline(
        transcriber: SpeechTranscriber?,
        prompt: String? = null,
        onResult: (ChunkResult) -> Unit = {},
    ) = ChunkPipeline(
        model = { transcriber },
        language = "en",
        prompt = prompt,
        now = { 0L },
        onResult = onResult,
    )

    @Test fun chunksAreTranscribedAndAssembledInOrder() {
        val pipeline = pipeline(LengthTranscriber())
        pipeline.submit(FloatArray(10))
        pipeline.submit(FloatArray(20))
        pipeline.submit(FloatArray(30))
        assertTrue(pipeline.finish(5_000))
        assertEquals("w10 w20 w30", pipeline.text())
        assertEquals(listOf(0, 1, 2), pipeline.results().map { it.seq })
        assertTrue(pipeline.missing().isEmpty())
    }

    @Test fun theDictionaryPromptAndLanguageReachTheModelForEveryChunk() {
        val transcriber = LengthTranscriber()
        val pipeline = pipeline(transcriber, prompt = "Glossary: Wirl, Kubernetes.")
        pipeline.submit(FloatArray(5))
        pipeline.submit(FloatArray(6))
        assertTrue(pipeline.finish(5_000))
        assertEquals(listOf("Glossary: Wirl, Kubernetes.", "Glossary: Wirl, Kubernetes."), transcriber.prompts)
        assertEquals(listOf("en", "en"), transcriber.languages)
    }

    @Test fun aFailedChunkIsReportedAndTheRestStillAssemble() {
        val pipeline = pipeline(LengthTranscriber(failOn = 20))
        pipeline.submit(FloatArray(10))
        pipeline.submit(FloatArray(20))
        pipeline.submit(FloatArray(30))
        assertTrue(pipeline.finish(5_000))
        assertEquals("w10 w30", pipeline.text())
        val failed = pipeline.results().single { it.seq == 1 }
        assertEquals("bad audio", failed.error)
        assertTrue(pipeline.missing().isEmpty())
    }

    @Test fun noLoadedModelMakesEveryChunkAnError() {
        val pipeline = pipeline(null)
        pipeline.submit(FloatArray(10))
        assertTrue(pipeline.finish(5_000))
        assertEquals("", pipeline.text())
        assertNotNull(pipeline.results().single().error)
    }

    @Test fun resultCallbackRunsOncePerChunkWithTheChunkSize() {
        val seen = CopyOnWriteArrayList<ChunkResult>()
        val pipeline = pipeline(LengthTranscriber()) { seen += it }
        pipeline.submit(FloatArray(7))
        pipeline.submit(FloatArray(8))
        assertTrue(pipeline.finish(5_000))
        assertEquals(listOf(7, 8), seen.map { it.samples }.sorted())
        assertNull(seen.firstOrNull { it.error != null })
    }

    /** Answers with fixed text, so a test can check what the pipeline keeps from it. */
    private class TextTranscriber(private val answer: String) : SpeechTranscriber {
        override fun transcribe(pcm: FloatArray, language: String, prompt: String?) = answer
        override fun close() = Unit
    }

    @Test fun aChunkOfSoundAnnotationsOrASilencePhraseIsDroppedAndNotInserted() {
        for (answer in listOf("[BLANK_AUDIO]", "Thanks for watching!")) {
            val pipeline = ChunkPipeline(model = { TextTranscriber(answer) }, language = "en", prompt = null, now = { 0L }, onResult = {})
            pipeline.submit(FloatArray(10))
            assertTrue(pipeline.finish(5_000))
            assertEquals("", pipeline.text())
            assertEquals("sound or silence only", pipeline.results().single().dropReason)
        }
    }

    @Test fun aRealSentenceWithAnAnnotationKeepsItsWords() {
        val pipeline = ChunkPipeline(model = { TextTranscriber("[beep] see you at 6") }, language = "en", prompt = null, now = { 0L }, onResult = {})
        pipeline.submit(FloatArray(10))
        assertTrue(pipeline.finish(5_000))
        assertEquals("see you at 6", pipeline.text())
        assertEquals(null, pipeline.results().single().dropReason)
    }
}
