package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Cantonese mode in the cleanup prompt. The block must stay identical to docs/cantonese.md (and Wirl for Windows). */
class CantonesePromptTest {
    @Test fun theBlockMatchesDocsCantoneseExactly() {
        val doc = File("../docs/cantonese.md").readText()
        val inDoc = doc.substringAfter("```text\n").substringBefore("\n```")
        assertEquals(CleanupPrompt.CANTONESE_MODE, inDoc)
        assertTrue(CleanupPrompt.CANTONESE_MODE.startsWith("--- CANTONESE MODE ---\n"))
        assertTrue(CleanupPrompt.CANTONESE_MODE.endsWith("\n--- END CANTONESE MODE ---"))
    }

    @Test fun cantoneseModeFollowsTheCorePromptAndPrecedesStyleNotes() {
        val prompt = CleanupPrompt.build("I write casually.", emptyList(), "我哋聽日見", cantonese = true)
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
        val prompt = CleanupPrompt.build("", listOf(DictionaryEntry("Sela", listOf("salad"))), "salad 好叻", cantonese = true)
        assertTrue(prompt.contains(CleanupPrompt.CANTONESE_MODE))
        assertTrue(prompt.contains("Sela"))
    }
}
