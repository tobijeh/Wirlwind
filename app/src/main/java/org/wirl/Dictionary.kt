package org.wirl

import org.json.JSONArray
import org.json.JSONObject

/** A word Wirl should get right: the correct spelling, the mishearings to watch for, and when they apply. */
data class DictionaryEntry(
    val term: String,
    val soundsLike: List<String> = emptyList(),
    val note: String = "",
)

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
        return DictionaryEntry(term, sounds, entry.note.trim())
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

    /** Folds an imported list into the existing one. */
    fun merge(existing: List<DictionaryEntry>, incoming: List<DictionaryEntry>): List<DictionaryEntry> =
        incoming.fold(existing) { acc, entry ->
            val withTerm = addTerm(acc, entry.term, entry.note)
            entry.soundsLike.fold(withTerm) { a, mishearing -> addMishearing(a, entry.term, mishearing) }
        }

    fun replace(entries: List<DictionaryEntry>, index: Int, entry: DictionaryEntry): List<DictionaryEntry> {
        val clean = normalized(entry) ?: return entries
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

/** The dictionary as JSON, for storage and for import/export files. */
object DictionaryJson {
    fun encode(entries: List<DictionaryEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            val sounds = JSONArray()
            entry.soundsLike.forEach { sounds.put(it) }
            array.put(JSONObject().put("term", entry.term).put("sounds_like", sounds).put("note", entry.note))
        }
        return array.toString(2)
    }

    /** Throws if [raw] isn't a JSON array. Entries without a term are skipped. */
    fun decode(raw: String): List<DictionaryEntry> {
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.getJSONObject(i)
            val sounds = obj.optJSONArray("sounds_like")
            val soundsLike = if (sounds == null) emptyList() else (0 until sounds.length()).map { sounds.optString(it) }
            DictionaryEditor.normalized(DictionaryEntry(obj.optString("term"), soundsLike, obj.optString("note")))
        }
    }
}
