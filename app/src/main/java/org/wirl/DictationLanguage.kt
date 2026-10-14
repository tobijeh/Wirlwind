package org.wirl

/** Which language dictation uses, and whether its offline speech pack is on the phone. */
object DictationLanguage {
    const val DEFAULT = "en-US"

    /** The language to use: the one picked in settings, otherwise the phone's, if we support it, otherwise English (US). */
    fun resolve(requested: String, deviceTag: String): String {
        if (requested.isNotBlank()) return requested
        return supportedMatch(deviceTag) ?: DEFAULT
    }

    /** An exact match for [tag], else a language match (e.g. "fr-CA" uses "fr-FR"), else null. */
    fun supportedMatch(tag: String): String? {
        val supported = DictationLanguages.options.map { it.first }.filter { it.isNotEmpty() }
        if (tag in supported) return tag
        val language = tag.substringBefore('-').lowercase()
        return supported.firstOrNull { it.substringBefore('-').lowercase() == language }
    }

    enum class PackStatus { INSTALLED, NOT_INSTALLED, UNKNOWN }

    /** [installedLanguages] is null when the phone can't say (Android before 14). */
    fun packStatus(language: String, installedLanguages: Collection<String>?): PackStatus = when {
        installedLanguages == null -> PackStatus.UNKNOWN
        installedLanguages.any { it.equals(language, ignoreCase = true) } -> PackStatus.INSTALLED
        else -> PackStatus.NOT_INSTALLED
    }

    fun describePack(status: PackStatus): String = when (status) {
        PackStatus.INSTALLED -> "installed (works offline)"
        PackStatus.NOT_INSTALLED -> "not installed (uses online recognition)"
        PackStatus.UNKNOWN -> "unknown on this Android version"
    }
}
