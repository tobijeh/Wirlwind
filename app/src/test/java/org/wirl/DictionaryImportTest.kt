package org.wirl

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Import formats, storage migration, and the repository's tombstones. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictionaryImportTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val id = "7d0f6b0e-3b1a-4c43-9a55-5d3c3c0a2f61"

    @Test fun importsTheSyncFormat() {
        val entries = DictionaryJson.decodeImport(
            """[{"id":"$id","term":"Sela","soundsLike":["salad","sella"],"note":"daughter","updatedAt":1767225600000,"deleted":false}]""",
        )
        assertEquals(listOf("salad", "sella"), entries.single().soundsLike)
        assertEquals(id, entries.single().id)
        assertEquals("daughter", entries.single().note)
    }

    @Test fun importSkipsTombstones() {
        val entries = DictionaryJson.decodeImport(
            """[{"id":"$id","term":"Sela","soundsLike":[],"note":"","updatedAt":1,"deleted":true},{"term":"Alex"}]""",
        )
        assertEquals(listOf("Alex"), entries.map { it.term })
    }

    @Test fun importsTheOldFormat() {
        val entries = DictionaryJson.decodeImport("""[{"term":"Sela","sounds_like":["salad"],"note":"web app"}]""")
        assertEquals(listOf(DictionaryEntry("Sela", listOf("salad"), "web app")), entries)
    }

    @Test fun importsSimpleListsWithACommaSeparatedString() {
        val entries = DictionaryJson.decodeImport("""[{"term":"Sela","soundsLike":"salad, Sella,, salad","note":"x"},{"term":"Bina"}]""")
        assertEquals(listOf("salad", "Sella"), entries[0].soundsLike)
        assertEquals("Bina", entries[1].term)
    }

    @Test fun importsSimpleListsWithAList() {
        assertEquals(listOf("salad"), DictionaryJson.decodeImport("""[{"term":"Sela","soundsLike":["salad"]}]""").single().soundsLike)
    }

    @Test fun importsAnEntriesObject() {
        assertEquals(listOf("Sela"), DictionaryJson.decodeImport("""{"entries":[{"term":"Sela"}]}""").map { it.term })
    }

    @Test fun importRejectsGarbage() {
        assertThrows(Exception::class.java) { DictionaryJson.decodeImport("not json") }
    }

    @Test fun importMergesIntoAnExistingTermInsteadOfDuplicating() {
        val existing = listOf(DictionaryEntry("Sela", listOf("salad"), "daughter", id, 5L))
        val incoming = DictionaryJson.decodeImport("""[{"term":"sela","soundsLike":"Sella, salad","note":"other"}]""")
        val merged = DictionaryEditor.merge(existing, incoming)
        assertEquals(1, merged.size)
        assertEquals(listOf("salad", "Sella"), merged[0].soundsLike)
        assertEquals("existing note is kept", "daughter", merged[0].note)
        assertEquals(id, merged[0].id)
    }

    @Test fun importedNewTermKeepsItsIdSoSyncDoesNotDuplicateIt() {
        val incoming = DictionaryJson.decodeImport("""[{"id":"$id","term":"Sela","soundsLike":[],"note":"","updatedAt":9,"deleted":false}]""")
        assertEquals(id, DictionaryEditor.merge(emptyList(), incoming).single().id)
    }

    @Test fun exportUsesTheSyncFormatAndLeavesOutTombstones() {
        val live = DictionaryEntry("Sela", listOf("salad"), "", id, 5L)
        val dead = DictionaryEntry("Alex", emptyList(), "", "7d0f6b0e-3b1a-4c43-9a55-5d3c3c0a2f62", 6L, deleted = true)
        val json = DictionaryJson.encodeExport(listOf(live, dead))
        assertTrue(json.contains("\"soundsLike\""))
        assertEquals(listOf(live), DictionaryJson.decode(json))
    }

    @Test fun storedOldFormatIsMigratedOnceWithStableIds() {
        context.getSharedPreferences("wirl", Context.MODE_PRIVATE).edit()
            .putInt(Prefs.KEY_SETTINGS_VERSION, 3)
            .putString(Prefs.KEY_DICTIONARY, """[{"term":"Sela","sounds_like":["salad","sella"],"note":"web app"}]""")
            .commit()
        val before = System.currentTimeMillis()
        val first = Prefs(context).dictionary.single()
        assertTrue(DictionarySync.isUuid(first.id))
        assertTrue(first.updatedAt >= before)
        assertEquals(listOf("salad", "sella"), first.soundsLike)
        assertEquals("web app", first.note)
        assertEquals(first, Prefs(context).dictionary.single())
        val raw = context.getSharedPreferences("wirl", Context.MODE_PRIVATE).getString(Prefs.KEY_DICTIONARY, "")!!
        assertTrue(raw.contains("\"soundsLike\"") && raw.contains("\"updatedAt\""))
    }

    @Test fun repositoryDeleteKeepsATombstoneButHidesIt() {
        val repo = DictionaryRepository(Prefs(context).also { it.dictionary = emptyList() })
        repo.update { DictionaryEditor.addTerm(it, "Sela") }
        repo.update { list -> DictionaryEditor.delete(list, 0) }
        assertTrue(repo.entries.value.isEmpty())
        assertTrue(repo.all.value.single().deleted)
        assertTrue("tombstones are stored, so the deletion syncs", Prefs(context).dictionary.single().deleted)
    }

    @Test fun repositoryEditKeepsTheIdAndBumpsUpdatedAt() {
        var clock = 1_000L
        val repo = DictionaryRepository(Prefs(context).also { it.dictionary = emptyList() }) { clock }
        repo.update { DictionaryEditor.addTerm(it, "Sela") }
        val before = repo.all.value.single()
        clock = 2_000L
        repo.update { list -> DictionaryEditor.replace(list, 0, DictionaryEntry("Selah")) }
        val after = repo.all.value.single()
        assertEquals(before.id, after.id)
        assertEquals("Selah", after.term)
        assertEquals(2_000L, after.updatedAt)
    }

    @Test fun applyRemoteMergesWithoutTriggeringAnotherSync() {
        val repo = DictionaryRepository(Prefs(context).also { it.dictionary = emptyList() })
        var changes = 0
        repo.onLocalChange = { changes++ }
        repo.update { DictionaryEditor.addTerm(it, "Sela") }
        repo.applyRemote(listOf(DictionaryEntry("Zoe", emptyList(), "", id, System.currentTimeMillis())))
        assertEquals(listOf("Sela", "Zoe"), repo.entries.value.map { it.term }.sorted())
        assertEquals(1, changes)
    }

    @Test fun timeAgo() {
        val now = 10L * 24 * 60 * 60 * 1000
        assertEquals(TimeAgo.Span.JustNow, TimeAgo.span(now - 30_000, now))
        assertEquals(TimeAgo.Span.Minutes(5), TimeAgo.span(now - 5 * 60_000, now))
        assertEquals(TimeAgo.Span.Hours(2), TimeAgo.span(now - 2 * 60 * 60_000, now))
        assertEquals(TimeAgo.Span.Days(3), TimeAgo.span(now - 3L * 24 * 60 * 60_000, now))
        assertEquals("5 min ago", TimeAgo.format(context, now - 5 * 60_000, now))
    }
}
