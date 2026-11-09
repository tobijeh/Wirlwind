package org.wirl

import org.json.JSONArray
import org.json.JSONObject

/**
 * A word Wirl should get right: the correct spelling, the mishearings to watch for, and when they apply.
 *
 * [id], [updatedAt] and [deleted] are the sync fields (docs/sync-protocol.md). A blank [id] means "not saved
 * yet": [DictionaryRepository] gives it a UUID and a timestamp when it's saved. Deleting keeps the entry as a
 * tombstone ([deleted] = true) so the deletion reaches the computer.
 */
data class DictionaryEntry(
    val term: String,
    val soundsLike: List<String> = emptyList(),
    val note: String = "",
    val id: String = "",
    val updatedAt: Long = 0L,
    val deleted: Boolean = false,
) {
    /** Same term, mishearings and note. Ignores the sync fields. */
    fun sameContent(other: DictionaryEntry): Boolean =
        term == other.term && soundsLike == other.soundsLike && note == other.note
}

/** Picks the entries that might apply to a piece of text. When unsure, an entry is included. */
object DictionaryMatcher {
    fun relevant(entries: List<DictionaryEntry>, text: String): List<DictionaryEntry> {
        val tokens = words(text)
        if (tokens.isEmpty()) return emptyList()
        return entries.filter { entry ->
            (listOf(entry.term) + entry.soundsLike).any { form ->
                words(form).any { word -> tokens.any { token -> sameSound(token, word) } }
            }
        }
    }

    private fun words(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }

    private fun sameSound(token: String, word: String): Boolean {
        if (token == word) return true
        if (token.length < 3 || word.length < 3) return false
        if (soundex(token) == soundex(word)) return true
        return token.length >= 4 && word.length >= 4 && levenshtein(token, word) <= 1
    }

    /** Classic Soundex: a letter followed by three digits, e.g. "Robert" -> "R163". */
    fun soundex(word: String): String {
        val letters = word.uppercase().filter { it in 'A'..'Z' }
        if (letters.isEmpty()) return ""
        val codes = mapOf(
            'B' to '1', 'F' to '1', 'P' to '1', 'V' to '1',
            'C' to '2', 'G' to '2', 'J' to '2', 'K' to '2', 'Q' to '2', 'S' to '2', 'X' to '2', 'Z' to '2',
            'D' to '3', 'T' to '3', 'L' to '4', 'M' to '5', 'N' to '5', 'R' to '6',
        )
        val out = StringBuilder().append(letters[0])
        var last = codes[letters[0]]
        for (c in letters.drop(1)) {
            val code = codes[c]
            if (code != null && code != last) out.append(code)
            if (c != 'H' && c != 'W') last = code
            if (out.length == 4) break
        }
        return out.toString().padEnd(4, '0')
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }
}

/** Adds the dictionary to the cleanup instructions. */
object DictionaryPrompt {
    const val INSTRUCTION =
        "Always spell these terms exactly as written. If a word sounds like a term or matches a listed " +
            "mishearing, and the context fits the note, replace it with the term:"

    /** The prompt for cleaning [text]: only the entries that might appear in it. */
    fun forText(basePrompt: String, entries: List<DictionaryEntry>, text: String): String =
        build(basePrompt, DictionaryMatcher.relevant(entries, text))

    fun build(basePrompt: String, entries: List<DictionaryEntry>): String {
        if (entries.isEmpty()) return basePrompt
        val lines = entries.joinToString("\n") { line(it) }
        return "$basePrompt\n\n$INSTRUCTION\n$lines"
    }

    private fun line(entry: DictionaryEntry): String = buildString {
        append("- \"").append(entry.term).append('"')
        if (entry.soundsLike.isNotEmpty()) {
            append(" (mishearings: ").append(entry.soundsLike.joinToString(", ")).append(')')
        }
        if (entry.note.isNotBlank()) append(" — context: ").append(entry.note)
    }
}

/** Add, edit, search, and merge operations. Every function returns a new list and never mutates its input. */
object DictionaryEditor {
    /** "salad, Sella,, salad" -> ["salad", "Sella"] */
    fun parseSoundsLike(raw: String): List<String> =
        raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

    /** Trims fields, drops a mishearing that equals the term, and removes duplicate mishearings. Null if there's no term. */
    fun normalized(entry: DictionaryEntry): DictionaryEntry? {
        val term = entry.term.trim()
        if (term.isEmpty()) return null
        val sounds = entry.soundsLike
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(term, ignoreCase = true) }
            .distinctBy { it.lowercase() }
        return entry.copy(term = term, soundsLike = sounds, note = entry.note.trim())
    }

    /** Alphabetical by term, ignoring case. */
    fun sorted(entries: List<DictionaryEntry>): List<DictionaryEntry> = entries.sortedBy { it.term.lowercase() }

    /** Position of [entry] in [entries] (by id once it has one, since a sync may have updated it), or -1 if it's gone. */
    fun indexOfEntry(entries: List<DictionaryEntry>, entry: DictionaryEntry?): Int {
        if (entry == null) return -1
        if (entry.id.isNotEmpty()) return entries.indexOfFirst { it.id == entry.id }
        return entries.indexOf(entry)
    }

    fun indexOfTerm(entries: List<DictionaryEntry>, term: String): Int =
        entries.indexOfFirst { it.term.equals(term.trim(), ignoreCase = true) }

    /** Adds a term. If it's already listed, nothing is duplicated; a blank note is filled in. */
    fun addTerm(entries: List<DictionaryEntry>, term: String, note: String = ""): List<DictionaryEntry> {
        val idx = indexOfTerm(entries, term)
        if (idx < 0) {
            val entry = normalized(DictionaryEntry(term, emptyList(), note)) ?: return entries
            return entries + entry
        }
        val existing = entries[idx]
        return replaceAt(entries, idx, existing.copy(note = existing.note.ifBlank { note.trim() }))
    }

    /** Adds a mishearing to its term, creating the term if needed. Never duplicates an entry. */
    fun addMishearing(
        entries: List<DictionaryEntry>,
        term: String,
        mishearing: String,
        note: String = "",
    ): List<DictionaryEntry> {
        val withTerm = addTerm(entries, term, note)
        val idx = indexOfTerm(withTerm, term)
        if (idx < 0) return withTerm
        val entry = withTerm[idx]
        val updated = normalized(entry.copy(soundsLike = entry.soundsLike + mishearing)) ?: return withTerm
        return replaceAt(withTerm, idx, updated)
    }

    /**
     * Puts a just-deleted entry back (undo). It keeps its id, so the sync sees one entry that was deleted and
     * restored. If the term was added again meanwhile, its details are merged into that entry instead.
     */
    fun restore(entries: List<DictionaryEntry>, entry: DictionaryEntry): List<DictionaryEntry> =
        if (indexOfTerm(entries, entry.term) >= 0 || entries.any { it.id.isNotEmpty() && it.id == entry.id }) {
            merge(entries, listOf(entry))
        } else {
            entries + entry.copy(deleted = false)
        }

    /** Folds an imported list into the existing one. If a term exists, new mishearings are added to it. */
    fun merge(existing: List<DictionaryEntry>, incoming: List<DictionaryEntry>): List<DictionaryEntry> =
        incoming.fold(existing) { acc, entry ->
            // A new term keeps its id from the file (e.g. a computer's export), so a later sync doesn't duplicate it.
            val clean = normalized(entry)
            if (clean != null && clean.id.isNotEmpty() && indexOfTerm(acc, clean.term) < 0 && acc.none { it.id == clean.id }) {
                return@fold acc + clean.copy(updatedAt = 0L, deleted = false)
            }
            val withTerm = addTerm(acc, entry.term, entry.note)
            entry.soundsLike.fold(withTerm) { a, mishearing -> addMishearing(a, entry.term, mishearing) }
        }

    /** Replaces the entry at [index]. Does nothing if the index is no longer valid (the list changed underneath). */
    fun replace(entries: List<DictionaryEntry>, index: Int, entry: DictionaryEntry): List<DictionaryEntry> {
        if (index !in entries.indices) return entries
        // The edited entry keeps the id of the one it replaces, so sync sees an edit, not a delete and an add.
        val clean = normalized(if (entry.id.isEmpty()) entry.copy(id = entries[index].id) else entry) ?: return entries
        return replaceAt(entries, index, clean)
    }

    fun delete(entries: List<DictionaryEntry>, index: Int): List<DictionaryEntry> =
        entries.filterIndexed { i, _ -> i != index }

    /** Case-insensitive search over the term, mishearings, and note. A blank query returns everything. */
    fun search(entries: List<DictionaryEntry>, query: String): List<DictionaryEntry> {
        val q = query.trim()
        if (q.isEmpty()) return entries
        return entries.filter { entry ->
            entry.term.contains(q, ignoreCase = true) ||
                entry.soundsLike.any { it.contains(q, ignoreCase = true) } ||
                entry.note.contains(q, ignoreCase = true)
        }
    }

    private fun replaceAt(entries: List<DictionaryEntry>, index: Int, entry: DictionaryEntry): List<DictionaryEntry> =
        entries.toMutableList().also { it[index] = entry }
}

/**
 * The dictionary as JSON, in the sync entry format (docs/sync-protocol.md):
 * `{"id", "term", "soundsLike": [..], "note", "updatedAt", "deleted"}`.
 */
object DictionaryJson {
    fun toJson(entry: DictionaryEntry): JSONObject {
        val sounds = JSONArray()
        entry.soundsLike.forEach { sounds.put(it) }
        return JSONObject()
            .put("id", entry.id)
            .put("term", entry.term)
            .put("soundsLike", sounds)
            .put("note", entry.note)
            .put("updatedAt", entry.updatedAt)
            .put("deleted", entry.deleted)
    }

    fun toJsonArray(entries: List<DictionaryEntry>): JSONArray =
        JSONArray().also { array -> entries.forEach { array.put(toJson(it)) } }

    /** Every entry, tombstones included. Used for storage and sync. */
    fun encode(entries: List<DictionaryEntry>): String = toJsonArray(entries).toString(2)

    /** The export file: live entries only, in the sync format. */
    fun encodeExport(entries: List<DictionaryEntry>): String = encode(entries.filterNot { it.deleted })

    /**
     * Reads stored or synced entries, tombstones included. Accepts the sync format and the old format
     * (`sounds_like`, no id). Entries without a term are skipped. Throws if [raw] isn't a list.
     */
    fun decode(raw: String): List<DictionaryEntry> = items(raw).mapNotNull { fromJson(it) }

    /** Reads entries from a sync payload array. */
    fun fromJsonArray(array: JSONArray): List<DictionaryEntry> =
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { fromJson(it) } }

    /**
     * Reads an import file: the sync format, the old format, or a simple list of `{"term", "soundsLike", "note"}`
     * where soundsLike is a list or a comma-separated string. A `{"entries": [...]}` object is accepted too.
     * Tombstones are skipped. Throws if [raw] isn't a list.
     */
    fun decodeImport(raw: String): List<DictionaryEntry> =
        items(raw).mapNotNull { fromJson(it) }.filterNot { it.deleted }.map { it.copy(updatedAt = 0L) }

    private fun items(raw: String): List<JSONObject> {
        val trimmed = raw.trim()
        val array = if (trimmed.startsWith("{")) JSONObject(trimmed).getJSONArray("entries") else JSONArray(trimmed)
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun fromJson(obj: JSONObject): DictionaryEntry? {
        val sounds: List<String> = when (val v = obj.opt("soundsLike") ?: obj.opt("sounds_like")) {
            is JSONArray -> (0 until v.length()).map { v.optString(it) }
            is String -> DictionaryEditor.parseSoundsLike(v)
            else -> emptyList()
        }
        val entry = DictionaryEntry(
            term = obj.optString("term"),
            soundsLike = sounds,
            note = if (obj.isNull("note")) "" else obj.optString("note"),
            id = obj.optString("id").trim().takeIf { DictionarySync.isUuid(it) }.orEmpty(),
            updatedAt = obj.optLong("updatedAt", 0L),
            deleted = obj.optBoolean("deleted", false),
        )
        return DictionaryEditor.normalized(entry)
    }
}
