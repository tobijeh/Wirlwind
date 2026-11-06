package org.wirl

import java.util.UUID

/**
 * Dictionary rules shared with Wirl for Windows (docs/sync-protocol.md): ids, timestamps, tombstones, and the
 * per-id merge. Every function is pure and returns a new list.
 */
object DictionarySync {
    /** Tombstones older than this are purged. */
    const val TOMBSTONE_MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000

    private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun isUuid(value: String): Boolean = UUID_PATTERN.matches(value)

    fun newId(): String = UUID.randomUUID().toString()

    /** Gives entries saved before sync existed an id and `updatedAt = now`. Already-migrated entries are kept. */
    fun migrate(entries: List<DictionaryEntry>, now: Long): List<DictionaryEntry> {
        val seen = HashSet<String>()
        return entries.map { e ->
            val id = e.id.takeIf { it.isNotEmpty() && seen.add(it) } ?: newId().also { seen.add(it) }
            if (id == e.id && e.updatedAt > 0) e else e.copy(id = id, updatedAt = if (e.updatedAt > 0) e.updatedAt else now)
        }
    }

    /**
     * Applies an edit of the visible list to the full stored list. [visible] is what the editor produced from
     * the live entries:
     * - an entry with a blank or unknown id is new: it gets an id (if needed) and `updatedAt = now`;
     * - an entry whose content changed, or that was a tombstone (undo), gets `updatedAt = max(now, old + 1)`;
     * - a live entry that's missing from [visible] becomes a tombstone, with a new `updatedAt`;
     * - tombstones stay, and old ones are purged.
     */
    fun reconcile(full: List<DictionaryEntry>, visible: List<DictionaryEntry>, now: Long): List<DictionaryEntry> {
        val before = full.associateBy { it.id }
        val seen = HashSet<String>()
        val out = ArrayList<DictionaryEntry>(full.size + 1)
        for (e in visible) {
            val id = e.id.takeIf { it.isNotEmpty() && it !in seen } ?: newId()
            seen += id
            val old = before[id]
            out += when {
                old == null -> e.copy(id = id, updatedAt = now, deleted = false)
                old.deleted || !old.sameContent(e) ->
                    e.copy(id = id, updatedAt = maxOf(now, old.updatedAt + 1), deleted = false)
                else -> old
            }
        }
        for (old in full) {
            if (old.id in seen) continue
            out += if (old.deleted) old else old.copy(deleted = true, updatedAt = maxOf(now, old.updatedAt + 1))
        }
        return purge(out, now)
    }

    /** Drops tombstones whose `updatedAt` is more than 90 days before [now]. */
    fun purge(entries: List<DictionaryEntry>, now: Long): List<DictionaryEntry> =
        entries.filterNot { it.deleted && now - it.updatedAt > TOMBSTONE_MAX_AGE_MS }

    /** The live entries, for the screen, cleanup and Whisper. */
    fun visible(entries: List<DictionaryEntry>): List<DictionaryEntry> = entries.filterNot { it.deleted }

    /**
     * The winner between two versions of the same id: newer `updatedAt` wins; on a tie, the deleted one wins; on a
     * full tie, the one whose canonical JSON sorts greater (the same rule as the computer).
     */
    fun pickWinner(a: DictionaryEntry, b: DictionaryEntry): DictionaryEntry = when {
        a.updatedAt != b.updatedAt -> if (a.updatedAt > b.updatedAt) a else b
        a.deleted != b.deleted -> if (a.deleted) a else b
        else -> if (canonicalJson(a) >= canonicalJson(b)) a else b
    }

    /** Per-id merge of two full dictionaries, tombstones included. Sorted by id, so both sides get the same list. */
    fun merge(local: List<DictionaryEntry>, remote: List<DictionaryEntry>): List<DictionaryEntry> {
        val map = LinkedHashMap<String, DictionaryEntry>()
        for (e in local + remote) {
            if (e.id.isEmpty()) continue
            map[e.id] = map[e.id]?.let { pickWinner(it, e) } ?: e
        }
        return map.values.sortedBy { it.id }
    }

    /**
     * Compact JSON with the field order and escaping of the computer's serializer (serde_json), so the full-tie
     * rule picks the same winner on both sides.
     */
    fun canonicalJson(e: DictionaryEntry): String = buildString {
        append("{\"id\":").append(quote(e.id))
        append(",\"term\":").append(quote(e.term))
        append(",\"soundsLike\":[").append(e.soundsLike.joinToString(",") { quote(it) }).append(']')
        append(",\"note\":").append(quote(e.note))
        append(",\"updatedAt\":").append(e.updatedAt)
        append(",\"deleted\":").append(e.deleted)
        append('}')
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
            }
        }
        append('"')
    }
}
