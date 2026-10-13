package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Test

class DictionaryMatcherTest {
    private val sela = DictionaryEntry(
        "Sela",
        listOf("salad", "Sella", "Stella"),
        "my web app; different from Selah, the biblical term",
    )
    private val alex = DictionaryEntry("Alex", listOf("Alax"))

    @Test fun listedMishearingMatches() {
        assertEquals(listOf(sela), DictionaryMatcher.relevant(listOf(sela, alex), "Can you check the salad app"))
    }

    @Test fun termMatchesRegardlessOfCase() {
        assertEquals(listOf(sela), DictionaryMatcher.relevant(listOf(sela), "send this to SELA"))
    }

    @Test fun closeSpellingMatchesByPronunciation() {
        assertEquals(listOf(sela), DictionaryMatcher.relevant(listOf(sela), "I asked Seela about it"))
    }

    @Test fun unrelatedTextIncludesNothing() {
        assertEquals(emptyList<DictionaryEntry>(), DictionaryMatcher.relevant(listOf(sela, alex), "Let's get lunch at noon tomorrow"))
    }

    @Test fun onlyTheMatchingEntryIsIncluded() {
        assertEquals(listOf(sela), DictionaryMatcher.relevant(listOf(sela, alex), "the salad was great"))
    }

    @Test fun emptyDictionaryOrTextMatchesNothing() {
        assertEquals(emptyList<DictionaryEntry>(), DictionaryMatcher.relevant(emptyList(), "salad"))
        assertEquals(emptyList<DictionaryEntry>(), DictionaryMatcher.relevant(listOf(sela), "   "))
    }

    @Test fun soundexKnownValues() {
        assertEquals("R163", DictionaryMatcher.soundex("Robert"))
        assertEquals("S400", DictionaryMatcher.soundex("Sela"))
        assertEquals("S400", DictionaryMatcher.soundex("sela"))
    }
}
