package org.wirl

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the Dictionary screen's state, the cleanup prompt, and quick add, all reading the same repository.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictionaryRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val base = "Clean it up."

    private fun fresh() = DictionaryRepository(Prefs(context).also { it.dictionary = emptyList() })

    private fun screen(repo: DictionaryRepository) = runBlocking { DictionaryViewModel(repo).state.first() }

    @Test fun addedEntryAppearsInScreenState() {
        val repo = fresh()
        repo.update { DictionaryEditor.addMishearing(it, "Sela", "salad", "web app") }

        val state = screen(repo)
        assertEquals(listOf("Sela"), state.entries.map { it.term })
        assertEquals(listOf("salad"), state.entries[0].soundsLike)
        assertEquals("web app", state.entries[0].note)
        assertEquals(1, state.total)
    }

    @Test fun addedEntryIsUsedByCleanupPrompt() {
        val repo = fresh()
        repo.update { DictionaryEditor.addMishearing(it, "Sela", "salad") }

        val prompt = DictionaryPrompt.forText(base, repo.entries.value, "I'm meeting salad at noon")
        assertTrue(prompt.contains("\"Sela\""))
    }

    @Test fun editShowsInListAndInCleanupPrompt() {
        val repo = fresh()
        repo.update { DictionaryEditor.addMishearing(it, "Sela", "salad") }

        repo.update { list ->
            DictionaryEditor.replace(list, DictionaryEditor.indexOfEntry(list, list[0]), DictionaryEntry("Selah", listOf("Sella"), "biblical term"))
        }

        assertEquals(listOf("Selah"), screen(repo).entries.map { it.term })
        assertEquals(listOf("Sella"), screen(repo).entries[0].soundsLike)
        val prompt = DictionaryPrompt.forText(base, repo.entries.value, "ask Sella about it")
        assertTrue(prompt.contains("\"Selah\" (mishearings: Sella) — context: biblical term"))
        assertFalse(prompt.contains("\"Sela\""))
    }

    @Test fun deleteRemovesFromListAndCleanupPrompt() {
        val repo = fresh()
        repo.update { DictionaryEditor.addMishearing(it, "Sela", "salad") }

        repo.update { list -> DictionaryEditor.delete(list, DictionaryEditor.indexOfEntry(list, list[0])) }

        val state = screen(repo)
        assertTrue(state.entries.isEmpty())
        assertEquals(0, state.total)
        assertEquals(base, DictionaryPrompt.forText(base, repo.entries.value, "I'm meeting salad at noon"))
    }

    @Test fun undoRestoresADeletedEntry() {
        val repo = fresh()
        repo.update { DictionaryEditor.addMishearing(it, "Sela", "salad", "web app") }
        val removed = repo.entries.value[0]

        repo.update { list -> DictionaryEditor.delete(list, DictionaryEditor.indexOfEntry(list, removed)) }
        repo.update { DictionaryEditor.merge(it, listOf(removed)) }

        assertEquals(listOf(removed), repo.entries.value)
    }

    @Test fun listIsSortedAlphabetically() {
        val repo = fresh()
        repo.update { list -> list + listOf(DictionaryEntry("zoe"), DictionaryEntry("Alex"), DictionaryEntry("bina")) }
        assertEquals(listOf("Alex", "bina", "zoe"), screen(repo).entries.map { it.term })
    }

    @Test fun emptyStateOnlyWhenNothingIsSaved() {
        val repo = fresh()
        assertEquals(0, screen(repo).total)

        repo.update { DictionaryEditor.addTerm(it, "Sela") }
        val filtered = runBlocking { DictionaryViewModel(repo).also { it.query.value = "zzz" }.state.first() }
        assertTrue(filtered.entries.isEmpty())
        assertEquals("no matches is not the same as no terms", 1, filtered.total)
    }

    @Test fun reloadPicksUpChangesMadeElsewhere() {
        val repo = fresh()
        Prefs(context).dictionary = listOf(DictionaryEntry("Alex"))
        repo.reload()
        assertEquals(listOf("Alex"), repo.entries.value.map { it.term })
    }

    @Test fun repositoryIsTheSameForTheWholeApp() {
        val a = DictionaryRepository.get(context)
        val b = DictionaryRepository.get(context)
        assertTrue(a === b)
    }
}
