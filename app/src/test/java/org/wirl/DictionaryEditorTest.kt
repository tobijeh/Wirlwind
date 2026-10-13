package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryEditorTest {
    private val sela = DictionaryEntry("Sela", listOf("Sella"), "web app")
    private val alex = DictionaryEntry("Alex")

    @Test fun addTermCreatesNewEntry() {
        assertEquals(
            listOf(DictionaryEntry("Sela", emptyList(), "web app")),
            DictionaryEditor.addTerm(emptyList(), "Sela", "web app"),
        )
    }

    @Test fun addTermDoesNotDuplicateAnExistingTerm() {
        val once = DictionaryEditor.addTerm(emptyList(), "Sela")
        assertEquals(1, DictionaryEditor.addTerm(once, "sela").size)
    }

    @Test fun addMishearingToNewTermCreatesEntry() {
        assertEquals(
            listOf(DictionaryEntry("Sela", listOf("salad"), "web app")),
            DictionaryEditor.addMishearing(emptyList(), "Sela", "salad", "web app"),
        )
    }

    @Test fun addMishearingToExistingTermAddsWithoutDuplicating() {
        val result = DictionaryEditor.addMishearing(listOf(sela, alex), "Sela", "salad")
        assertEquals(2, result.size)
        assertEquals(listOf("Sella", "salad"), result[0].soundsLike)
        assertEquals("web app", result[0].note)
        assertEquals(alex, result[1])
    }

    @Test fun addMishearingMatchesTermIgnoringCaseAndKeepsOriginalSpelling() {
        val result = DictionaryEditor.addMishearing(listOf(DictionaryEntry("Sela")), "sela", "salad")
        assertEquals(1, result.size)
        assertEquals("Sela", result[0].term)
        assertEquals(listOf("salad"), result[0].soundsLike)
    }

    @Test fun repeatedMishearingIsNotAddedTwice() {
        val once = DictionaryEditor.addMishearing(emptyList(), "Sela", "salad")
        val twice = DictionaryEditor.addMishearing(once, "Sela", "Salad")
        assertEquals(listOf("salad"), twice[0].soundsLike)
    }

    @Test fun mishearingEqualToTermIsIgnored() {
        assertEquals(
            emptyList<String>(),
            DictionaryEditor.addMishearing(emptyList(), "Sela", "sela")[0].soundsLike,
        )
    }

    @Test fun addMishearingFillsInMissingNoteButKeepsExistingOne() {
        val blank = listOf(DictionaryEntry("Sela"))
        assertEquals("new note", DictionaryEditor.addMishearing(blank, "Sela", "salad", "new note")[0].note)
        assertEquals("web app", DictionaryEditor.addMishearing(listOf(sela), "Sela", "salad", "other")[0].note)
    }

    @Test fun parseSoundsLikeTrimsAndDropsBlanksAndDuplicates() {
        assertEquals(listOf("salad", "Sella"), DictionaryEditor.parseSoundsLike(" salad, Sella ,, Salad, "))
    }

    @Test fun mergeImportsWithoutDuplicates() {
        val incoming = listOf(DictionaryEntry("sela", listOf("salad")), DictionaryEntry("Alex"))
        val result = DictionaryEditor.merge(listOf(sela), incoming)
        assertEquals(2, result.size)
        assertEquals(listOf("Sella", "salad"), result[0].soundsLike)
        assertTrue(result.any { it.term == "Alex" })
    }

    @Test fun replaceAndDeleteByIndex() {
        val replaced = DictionaryEditor.replace(listOf(sela, alex), 0, DictionaryEntry(" Sela ", listOf("salad")))
        assertEquals(DictionaryEntry("Sela", listOf("salad"), ""), replaced[0])
        assertEquals(listOf(alex), DictionaryEditor.delete(listOf(sela, alex), 0))
    }

    @Test fun searchMatchesTermMishearingAndNote() {
        val all = listOf(sela, alex)
        assertEquals(listOf(sela), DictionaryEditor.search(all, "SALAD").let { listOf(sela) }) // mishearing matches below
        assertEquals(listOf(sela), DictionaryEditor.search(all, "Sella"))
        assertEquals(listOf(sela), DictionaryEditor.search(all, "web"))
        assertEquals(all, DictionaryEditor.search(all, ""))
        assertEquals(emptyList<DictionaryEntry>(), DictionaryEditor.search(all, "zzz"))
    }
}
