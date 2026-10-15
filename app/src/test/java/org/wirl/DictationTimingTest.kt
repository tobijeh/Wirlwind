package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationTimingTest {
    private fun chunk(seq: Int, ms: Long) = ChunkResult(seq, "t$seq", null, ms, 16_000)

    @Test fun eachStageHasItsOwnLine() {
        val timing = DictationTiming(
            whisper = WhisperTiming(modelWaitMs = 0, finalChunkMs = 420, totalMs = 900, chunks = 3, duringRecording = 2),
            claude = ClaudeCallTiming(firstByteMs = 800, totalMs = 1_100, connectionReused = true, outputTokens = 37, thinkingUsed = false),
            insertMs = 35,
            stopToInsertMs = 2_050,
        )
        val lines = timing.lines()
        assertTrue(lines.any { it == "whisper model wait: 0 ms" })
        assertTrue(lines.any { it == "whisper final chunk: 420 ms" })
        assertTrue(lines.any { it == "whisper total: 900 ms, 3 chunks (2 during recording)" })
        assertTrue(lines.any { it == "claude first byte: 800 ms, total: 1100 ms, connection: reused" })
        assertTrue(lines.any { it == "claude output tokens: 37, thinking: not used" })
        assertTrue(lines.any { it == "insert: 35 ms" })
        assertTrue(lines.any { it == "stop to insert: 2050 ms" })
    }

    @Test fun stagesThatDidNotRunAreLeftOut() {
        // Google's recognizer has no Whisper stages, and hold-to-clean has no dictation stop.
        val lines = DictationTiming(insertMs = 10).lines()
        assertEquals(listOf("insert: 10 ms"), lines)
        assertTrue(DictationTiming().lines().isEmpty())
    }

    @Test fun aNewConnectionAndThinkingAreSaidOutright() {
        val lines = DictationTiming(
            claude = ClaudeCallTiming(firstByteMs = 1, totalMs = 2, connectionReused = false, outputTokens = null, thinkingUsed = true),
        ).lines()
        assertTrue(lines.any { it.endsWith("connection: new") })
        assertTrue(lines.any { it == "claude output tokens: unknown, thinking: used" })
    }

    @Test fun theFinalChunkIsTheOneCutAtStop() {
        val outcome = WhisperOutcome(
            text = "a b c",
            chunks = listOf(chunk(2, 300), chunk(0, 900), chunk(1, 800)),
            missing = emptyList(),
            finished = true,
            stopToTextMs = 500,
            duringRecording = 2,
            modelWaitMs = 0,
        )
        assertEquals(300L, outcome.finalChunk()?.transcribeMs)
        val timing = outcome.timing()
        assertEquals(300L, timing.finalChunkMs)
        assertEquals(500L, timing.totalMs)
        assertEquals(3, timing.chunks)
        assertFalse(outcome.finalChunk() == null)
    }

    @Test fun aDictationWithNoChunksHasNoFinalChunk() {
        val outcome = WhisperOutcome("", emptyList(), emptyList(), true, 5, 0, 0)
        assertEquals(null, outcome.finalChunk())
        assertEquals(null, outcome.timing().finalChunkMs)
    }
}
