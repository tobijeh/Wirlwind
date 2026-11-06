package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ids, timestamps, tombstones and the per-id merge (docs/sync-protocol.md §1 and §7). Plain JUnit. */
class DictionarySyncTest {
    private val day = 24L * 60 * 60 * 1000

    private fun e(id: String, term: String, at: Long, deleted: Boolean = false, sounds: List<String> = emptyList()) =
        DictionaryEntry(term, sounds, "", id, at, deleted)

    private val idA = "00000000-0000-4000-8000-00000000000a"
    private val idB = "00000000-0000-4000-8000-00000000000b"
    private val idC = "00000000-0000-4000-8000-00000000000c"

    // ---- migration ----

    @Test fun migrationGivesOldEntriesIdsAndNow() {
        val old = listOf(DictionaryEntry("Sela", listOf("salad")), DictionaryEntry("Alex"))
        val migrated = DictionarySync.migrate(old, 1_000L)
        assertTrue(migrated.all { DictionarySync.isUuid(it.id) })
        assertEquals(2, migrated.map { it.id }.toSet().size)
        assertTrue(migrated.all { it.updatedAt == 1_000L && !it.deleted })
        assertEquals(listOf("salad"), migrated[0].soundsLike)
    }

    @Test fun migrationKeepsAlreadyMigratedEntries() {
        val entries = listOf(e(idA, "Sela", 5L))
        assertEquals(entries, DictionarySync.migrate(entries, 1_000L))
    }

    @Test fun migrationFixesDuplicateIds() {
        val migrated = DictionarySync.migrate(listOf(e(idA, "Sela", 5L), e(idA, "Alex", 5L)), 1_000L)
        assertEquals(idA, migrated[0].id)
        assertNotEquals(idA, migrated[1].id)
    }

    // ---- local edits ----

    @Test fun newEntryGetsIdAndTimestamp() {
        val full = DictionarySync.reconcile(emptyList(), listOf(DictionaryEntry("Sela")), 1_000L)
        assertEquals(1, full.size)
        assertTrue(DictionarySync.isUuid(full[0].id))
        assertEquals(1_000L, full[0].updatedAt)
    }

    @Test fun editKeepsIdAndUpdatedAtStrictlyIncreases() {
        val full = listOf(e(idA, "Sela", 5_000L))
        val edited = DictionarySync.reconcile(full, listOf(full[0].copy(note = "web app")), 1_000L)
        assertEquals(idA, edited[0].id)
        assertEquals("clock behind: still newer", 5_001L, edited[0].updatedAt)
        assertEquals("web app", edited[0].note)
    }

    @Test fun unchangedEntryKeepsItsTimestamp() {
        val full = listOf(e(idA, "Sela", 5L))
        assertEquals(full, DictionarySync.reconcile(full, full, 1_000L))
    }

    @Test fun deleteLeavesATombstoneAndHidesIt() {
        val full = listOf(e(idA, "Sela", 5L), e(idB, "Alex", 5L))
        val after = DictionarySync.reconcile(full, listOf(full[1]), 1_000L)
        val tomb = after.single { it.id == idA }
        assertTrue(tomb.deleted)
        assertEquals(1_000L, tomb.updatedAt)
        assertEquals(listOf("Alex"), DictionarySync.visible(after).map { it.term })
    }

    @Test fun undoRestoresTheSameIdWithANewerTimestamp() {
        val tomb = e(idA, "Sela", 1_000L, deleted = true)
        val restored = DictionarySync.reconcile(listOf(tomb), listOf(tomb.copy(deleted = false)), 1_000L)
        assertEquals(idA, restored[0].id)
        assertFalse(restored[0].deleted)
        assertEquals(1_001L, restored[0].updatedAt)
    }

    @Test fun tombstonesArePurgedAfter90Days() {
        val tomb = e(idA, "Sela", 0L, deleted = true)
        assertEquals(listOf(tomb), DictionarySync.purge(listOf(tomb), 90 * day))
        assertTrue(DictionarySync.purge(listOf(tomb), 90 * day + 1).isEmpty())
        assertEquals("live entries are never purged", 1, DictionarySync.purge(listOf(e(idB, "Alex", 0L)), 365 * day).size)
    }

    // ---- merge ----

    @Test fun newerVersionWins() {
        val phone = listOf(e(idA, "Sela", 10L))
        val computer = listOf(e(idA, "Selah", 20L))
        assertEquals("Selah", DictionarySync.merge(phone, computer).single().term)
        assertEquals("Selah", DictionarySync.merge(computer, phone).single().term)
    }

    @Test fun onATieDeletedWins() {
        val live = e(idA, "Sela", 10L)
        val dead = e(idA, "Sela", 10L, deleted = true)
        assertTrue(DictionarySync.merge(listOf(live), listOf(dead)).single().deleted)
        assertTrue(DictionarySync.merge(listOf(dead), listOf(live)).single().deleted)
    }

    @Test fun fullTieIsDecidedTheSameWayOnBothSides() {
        val a = e(idA, "Sela", 10L)
        val b = e(idA, "Selah", 10L)
        assertEquals(DictionarySync.merge(listOf(a), listOf(b)), DictionarySync.merge(listOf(b), listOf(a)))
    }

    @Test fun editsOnBothSidesToDifferentEntriesAreAllKept() {
        val base = listOf(e(idA, "Sela", 10L), e(idB, "Alex", 10L))
        val phone = listOf(base[0].copy(note = "web app", updatedAt = 20L), base[1])
        val computer = listOf(base[0], base[1].copy(soundsLike = listOf("Alec"), updatedAt = 30L))
        val merged = DictionarySync.merge(phone, computer)
        assertEquals("web app", merged.single { it.id == idA }.note)
        assertEquals(listOf("Alec"), merged.single { it.id == idB }.soundsLike)
        assertEquals("both sides end identical", merged, DictionarySync.merge(computer, phone))
    }

    @Test fun sameTermAddedOnBothSidesKeepsBothIdsAndBothSidesMatch() {
        val phone = listOf(e(idA, "Sela", 10L, sounds = listOf("salad")))
        val computer = listOf(e(idB, "Sela", 12L, sounds = listOf("sella")))
        val merged = DictionarySync.merge(phone, computer)
        assertEquals(listOf(idA, idB), merged.map { it.id })
        assertEquals(merged, DictionarySync.merge(computer, phone))
    }

    @Test fun deletionOnOneSideReachesTheOther() {
        val computer = listOf(e(idA, "Sela", 10L), e(idB, "Alex", 10L))
        val phone = DictionarySync.reconcile(computer, listOf(computer[1]), 20L)
        val merged = DictionarySync.merge(computer, phone)
        assertEquals(listOf("Alex"), DictionarySync.visible(merged).map { it.term })
        assertTrue(merged.single { it.id == idA }.deleted)
    }

    @Test fun editAfterDeleteElsewhereBringsItBackOnlyIfNewer() {
        val tomb = e(idA, "Sela", 20L, deleted = true)
        assertTrue(DictionarySync.merge(listOf(tomb), listOf(e(idA, "Sela", 15L))).single().deleted)
        assertFalse(DictionarySync.merge(listOf(tomb), listOf(e(idA, "Sela!", 25L))).single().deleted)
    }

    @Test fun offlinePhoneCatchesUp() {
        // Both start from the same synced list.
        var phone = listOf(e(idA, "Sela", 10L), e(idB, "Alex", 10L))
        var computer = phone
        // Phone offline: adds a term and edits Sela. Computer meanwhile: deletes Alex and adds its own term.
        phone = DictionarySync.reconcile(phone, DictionarySync.visible(phone).map {
            if (it.id == idA) it.copy(note = "daughter") else it
        } + DictionaryEntry("Bina"), 100L)
        computer = DictionarySync.reconcile(computer, listOf(computer[0], e(idC, "Zoe", 0L)), 50L)
        // The phone comes back: it sends its list, the computer merges and returns the result, the phone keeps it.
        val onComputer = DictionarySync.merge(computer, phone)
        val onPhone = DictionarySync.merge(phone, onComputer)
        assertEquals(onComputer, onPhone)
        assertEquals(listOf("Bina", "Sela", "Zoe"), DictionarySync.visible(onPhone).map { it.term }.sorted())
        assertEquals("daughter", onPhone.single { it.id == idA }.note)
    }

    @Test fun canonicalJsonMatchesTheComputersSerializer() {
        val entry = DictionaryEntry("Sé\"la", listOf("a/b"), "line\nbreak", idA, 7L, false)
        assertEquals(
            """{"id":"$idA","term":"Sé\"la","soundsLike":["a/b"],"note":"line\nbreak","updatedAt":7,"deleted":false}""",
            DictionarySync.canonicalJson(entry),
        )
    }
}
