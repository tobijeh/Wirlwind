package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryPromptTest {
    private val base = "Clean it up."
    private val sela = DictionaryEntry(
        "Sela",
        listOf("salad", "Sella", "Stella"),
        "my web app; different from Selah, the biblical term",
    )

    @Test fun noEntriesLeavesPromptUnchanged() {
        assertEquals(base, DictionaryPrompt.build(base, emptyList()))
    }

    @Test fun entriesAddInstructionAndLines() {
        val prompt = DictionaryPrompt.build(base, listOf(sela))
        assertTrue(prompt.startsWith(base))
        assertTrue(prompt.contains(DictionaryPrompt.INSTRUCTION))
        assertTrue(
            prompt.contains(
                "- \"Sela\" (mishearings: salad, Sella, Stella) — context: my web app; different from Selah, the biblical term",
            ),
        )
    }

    @Test fun instructionMatchesTheSpec() {
        assertEquals(
            "Always spell these terms exactly as written. If a word sounds like a term or matches a listed mishearing, " +
                "and the context fits the note, replace it with the term:",
            DictionaryPrompt.INSTRUCTION,
        )
    }

    @Test fun entryWithoutMishearingsOrNoteHasNoExtras() {
        val prompt = DictionaryPrompt.build(base, listOf(DictionaryEntry("Alex")))
        assertTrue(prompt.endsWith("\n- \"Alex\""))
        assertFalse(prompt.contains("(mishearings:"))
        assertFalse(prompt.contains("— context:"))
    }

    @Test fun forTextIncludesOnlyEntriesThatMightAppear() {
        val alex = DictionaryEntry("Alex")
        val prompt = DictionaryPrompt.forText(base, listOf(sela, alex), "I'm meeting salad at noon")
        assertTrue(prompt.contains("\"Sela\""))
        assertFalse(prompt.contains("\"Alex\""))
    }
}
