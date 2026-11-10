package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Cantonese and Mandarin modes in the cleanup prompt. Each block must stay identical to its doc (and Wirl for Windows). */
class CantonesePromptTest {
    @Test fun theBlockMatchesDocsCantoneseExactly() {
        val doc = File("../docs/cantonese.md").readText()
        val inDoc = doc.substringAfter("```text\n").substringBefore("\n```")
        assertEquals(CleanupPrompt.CANTONESE_MODE, inDoc)
        assertTrue(CleanupPrompt.CANTONESE_MODE.startsWith("--- CANTONESE MODE ---\n"))
        assertTrue(CleanupPrompt.CANTONESE_MODE.endsWith("\n--- END CANTONESE MODE ---"))
    }

    @Test fun cantoneseModeFollowsTheCorePromptAndPrecedesStyleNotes() {
        val prompt = CleanupPrompt.build("I write casually.", emptyList(), "我哋聽日見", mode = CleanupMode.CANTONESE)
        assertTrue(prompt.startsWith(CleanupPrompt.CORE))
        val block = prompt.indexOf(CleanupPrompt.CANTONESE_MODE)
        val notes = prompt.indexOf("Speaker's style preferences:\nI write casually.")
        assertTrue(block > CleanupPrompt.CORE.length - 1)
        assertTrue("style notes come after the Cantonese block", notes > block)
    }

    @Test fun otherDictationsDontGetIt() {
        val prompt = CleanupPrompt.build("", emptyList(), "See you tomorrow")
        assertFalse(prompt.contains("CANTONESE MODE"))
        assertEquals(CleanupPrompt.CORE, prompt)
    }

    @Test fun theDictionaryStillAppliesInCantoneseMode() {
        val prompt = CleanupPrompt.build("", listOf(DictionaryEntry("Sela", listOf("salad"))), "salad 好叻", mode = CleanupMode.CANTONESE)
        assertTrue(prompt.contains(CleanupPrompt.CANTONESE_MODE))
        assertTrue(prompt.contains("Sela"))
    }

    @Test fun theMandarinBlockMatchesDocsMandarinExactly() {
        val doc = File("../docs/mandarin.md").readText()
        assertEquals(CleanupPrompt.MANDARIN_MODE, doc.substringAfter("```text\n").substringBefore("\n```"))
        assertTrue(CleanupPrompt.MANDARIN_MODE.contains("simplified characters"))
    }

    @Test fun mandarinModeReplacesNotAddsToCantonese() {
        val prompt = CleanupPrompt.build("Short messages.", emptyList(), "我们明天见", mode = CleanupMode.MANDARIN)
        assertTrue(prompt.startsWith(CleanupPrompt.CORE))
        assertTrue(prompt.contains(CleanupPrompt.MANDARIN_MODE))
        assertFalse(prompt.contains("CANTONESE MODE"))
        assertTrue(prompt.indexOf("Speaker's style preferences:") > prompt.indexOf(CleanupPrompt.MANDARIN_MODE))
    }

    @Test fun eachModelPicksItsCleanupMode() {
        assertEquals(CleanupMode.STANDARD, WhisperModel.FAST.language.cleanupMode)
        assertEquals(CleanupMode.STANDARD, WhisperModel.ACCURATE.language.cleanupMode)
        assertEquals(CleanupMode.CANTONESE, WhisperModel.CANTONESE.language.cleanupMode)
        assertEquals(CleanupMode.MANDARIN, WhisperModel.MANDARIN.language.cleanupMode)
    }
}
