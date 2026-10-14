package org.wirl

/** Languages offered for dictation. An empty tag means "use the phone's language". */
object DictationLanguages {
    val options: List<Pair<String, String>> = listOf(
        "" to "Device language",
        "en-US" to "English (US)",
        "en-GB" to "English (UK)",
        "es-ES" to "Español",
        "fr-FR" to "Français",
        "de-DE" to "Deutsch",
        "it-IT" to "Italiano",
        "pt-BR" to "Português (Brasil)",
        "nl-NL" to "Nederlands",
        "ja-JP" to "日本語",
    )

    fun labelFor(tag: String): String = options.firstOrNull { it.first == tag }?.second ?: options[0].second
}
