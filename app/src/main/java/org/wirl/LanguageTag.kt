package org.wirl

/** Formats language tags the way recognizers expect: BCP-47, with a hyphen and a region in capitals. */
object LanguageTag {
    /** "en_us" -> "en-US", " fr-fr " -> "fr-FR", "de" -> "de". Blank stays blank. */
    fun format(raw: String): String {
        val parts = raw.trim().replace('_', '-').split('-').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        return buildList {
            add(parts[0].lowercase())
            parts.drop(1).forEach { part ->
                add(
                    when {
                        part.length == 2 && part.all { it.isLetter() } -> part.uppercase()
                        part.length == 4 && part.all { it.isLetter() } -> part.lowercase().replaceFirstChar { it.uppercase() }
                        else -> part.lowercase()
                    },
                )
            }
        }.joinToString("-")
    }
}
