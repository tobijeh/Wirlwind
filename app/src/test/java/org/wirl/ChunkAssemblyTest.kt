package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkAssemblyTest {
    @Test fun chunksAssembleInSpokenOrderEvenIfTheyFinishLate() {
        val assembler = ChunkAssembler()
        assembler.add(0, "Hello there.")
        assembler.add(2, "and goodbye.")
        assembler.add(1, "How are you?")
        assertEquals("Hello there. How are you? and goodbye.", assembler.text())
        assertTrue(assembler.missing().isEmpty())
    }

    @Test fun aRepeatedChunkIsIgnoredSoNoTextIsDuplicated() {
        val assembler = ChunkAssembler()
        assertTrue(assembler.add(0, "one"))
        assertTrue(assembler.add(1, "two"))
        assertFalse(assembler.add(1, "two"))
        assertEquals("one two", assembler.text())
        assertEquals(1, assembler.duplicates)
    }

    @Test fun aChunkThatNeverArrivedIsReportedAsMissing() {
        val assembler = ChunkAssembler()
        assembler.add(0, "first")
        assembler.add(2, "third")
        assertEquals(listOf(1), assembler.missing())
        assertEquals("first third", assembler.text())
    }

    @Test fun emptyChunksAndExtraSpacesDoNotLeaveGapsInTheText() {
        val assembler = ChunkAssembler()
        assembler.add(0, "  spaced   out ")
        assembler.add(1, "")
        assembler.add(2, "text")
        assertEquals("spaced out text", assembler.text())
    }
}
