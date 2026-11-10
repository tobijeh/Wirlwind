package org.wirl

/** Whether Google's offline speech pack for the dictation language is on the phone. The language comes from the model. */
object DictationLanguage {
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
