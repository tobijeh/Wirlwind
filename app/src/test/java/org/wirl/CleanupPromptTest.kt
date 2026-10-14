package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupPromptTest {
    private val entries = listOf(DictionaryEntry("Sela", listOf("salad")))

    @Test fun coreIsAlwaysIncluded() {
        assertTrue(CleanupPrompt.build("", emptyList(), "text").startsWith(CleanupPrompt.CORE))
        assertTrue(CleanupPrompt.build("Casual.", entries, "text").startsWith(CleanupPrompt.CORE))
    }

    @Test fun coreMatchesTheRequiredText() {
        assertTrue(CleanupPrompt.CORE.startsWith("You clean up text that was dictated by voice"))
        assertTrue(CleanupPrompt.CORE.contains("Is it still a pro, juicing that spacebar thing' becomes 'Is it still producing that spacebar thing'"))
        assertTrue(CleanupPrompt.CORE.endsWith("Return only the cleaned text, with no preamble or quotes."))
    }

    @Test fun styleNotesAreAppendedOnlyWhenPresent() {
        assertFalse(CleanupPrompt.build("", emptyList(), "text").contains("Speaker's style preferences"))
        assertFalse(CleanupPrompt.build("   ", emptyList(), "text").contains("Speaker's style preferences"))
        val withNotes = CleanupPrompt.build("I write casually, no em-dashes", emptyList(), "text")
        assertTrue(withNotes.contains("Speaker's style preferences:\nI write casually, no em-dashes"))
        assertTrue(withNotes.indexOf("Speaker's style preferences") > CleanupPrompt.CORE.length - 1)
    }

    @Test fun alternativesAreIncludedOnlyWhenGiven() {
        assertFalse(CleanupPrompt.build("", entries, "text").contains("alternative transcriptions"))
        val withAlternatives = CleanupPrompt.build("", entries, "text", listOf("text two", "text three"))
        assertTrue(withAlternatives.contains(CleanupPrompt.ALTERNATIVES_INSTRUCTION))
        assertTrue(withAlternatives.contains("Alternative 1: text two"))
        assertTrue(withAlternatives.contains("Alternative 2: text three"))
    }

    @Test fun dictionaryIsStillIncludedForRelevantText() {
        assertTrue(CleanupPrompt.build("", entries, "I met salad").contains("\"Sela\""))
        assertFalse(CleanupPrompt.build("", entries, "let's get lunch").contains("\"Sela\""))
    }
}
