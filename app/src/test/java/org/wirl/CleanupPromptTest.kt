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
        assertTrue(CleanupPrompt.CORE.startsWith("You are an expert editor turning spoken dictation into the message the speaker intended to write."))
        assertTrue(CleanupPrompt.CORE.contains("'no, actually,' 'I mean,' 'wait,' 'sorry,' 'not that, this'"))
        assertTrue(CleanupPrompt.CORE.contains("'new paragraph,' 'new line,' 'scratch that,' 'delete that last part,' 'make that a list,' 'put that in quotes,' 'question mark,' 'all caps'"))
        assertTrue(CleanupPrompt.CORE.contains("'tell him no, actually it's fine'"))
        assertTrue(CleanupPrompt.CORE.contains("becomes 'Is it still producing that spacebar thing.'"))
        assertTrue(CleanupPrompt.CORE.endsWith("Return only the final text, with no preamble, notes, or quotes."))
    }

    @Test fun theOldTranscriberPromptIsGone() {
        assertFalse(CleanupPrompt.CORE.contains("You clean up text that was dictated by voice"))
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

    @Test fun theRulesTellClaudeToIgnoreSoundsAndReturnNothingForNoise() {
        assertTrue(CleanupPrompt.CORE.contains("- Never describe sounds, noises, or silence, and never add bracketed annotations."))
        assertTrue(CleanupPrompt.CORE.contains("- If the input contains no intended words (only noise, sound descriptions, or nothing meaningful), return an empty response."))
    }
}
