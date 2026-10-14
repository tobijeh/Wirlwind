package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Test

class AlternativesTest {
    @Test fun topResultIsAlwaysKept() {
        assertEquals(listOf("hello"), Alternatives.filter(listOf("hello"), floatArrayOf(0.01f)))
    }

    @Test fun lowConfidenceAlternativesAreDropped() {
        val kept = Alternatives.filter(listOf("top", "good alt", "bad alt"), floatArrayOf(0.9f, 0.6f, 0.1f))
        assertEquals(listOf("top", "good alt"), kept)
    }

    @Test fun unknownScoresAreKept() {
        assertEquals(listOf("top", "alt"), Alternatives.filter(listOf("top", "alt"), null))
        assertEquals(listOf("top", "alt"), Alternatives.filter(listOf("top", "alt"), floatArrayOf(0.9f, -1f)))
    }

    @Test fun atMostFiveResultsAreKept() {
        val results = (1..8).map { "r$it" }
        assertEquals(5, Alternatives.filter(results, null).size)
    }

    @Test fun blankResultsAreIgnored() {
        assertEquals(listOf("top", "alt"), Alternatives.filter(listOf("top", "  ", "alt"), null))
    }

    @Test fun assemblerOffersWholeDictationAlternatives() {
        val t = TranscriptAssembler()
        t.commit("I'm meeting Sella", listOf("I'm meeting Stella"))
        t.commit("at noon", listOf("at noon", "at nine"))
        assertEquals("I'm meeting Sella at noon", t.text())
        assertEquals(listOf("I'm meeting Stella at noon", "I'm meeting Sella at nine"), t.alternatives())
    }

    @Test fun assemblerUsesTheTopWhenASegmentHasNoAlternative() {
        val t = TranscriptAssembler()
        t.commit("first part", listOf("first pard"))
        t.commit("second part")
        assertEquals(listOf("first pard second part"), t.alternatives())
    }

    @Test fun assemblerNeverOffersTheSameTextTwice() {
        val t = TranscriptAssembler()
        t.commit("same", listOf("same"))
        assertEquals(emptyList<String>(), t.alternatives())
    }

    @Test fun assemblerIncludesAPendingPartial() {
        val t = TranscriptAssembler()
        t.commit("hello", listOf("hallo"))
        t.partial("there")
        assertEquals(listOf("hallo there"), t.alternatives())
    }
}
