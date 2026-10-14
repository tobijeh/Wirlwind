package org.wirl

/**
 * Which speech recognizer dictation uses. Checked in this order, first available wins:
 * 1. the on-device recognizer (Android 12+),
 * 2. Google's speech service, by its component name (Android 12+),
 * 3. the system default recognizer.
 */
object SpeechEngine {
    enum class Choice { ON_DEVICE, GOOGLE, SYSTEM, NONE }

    fun choose(onDeviceAvailable: Boolean, googleAvailable: Boolean, systemAvailable: Boolean): Choice = when {
        onDeviceAvailable -> Choice.ON_DEVICE
        googleAvailable -> Choice.GOOGLE
        systemAvailable -> Choice.SYSTEM
        else -> Choice.NONE
    }

    /** What the phone offers. [googleComponent] is "package/class" for Google's service, or null if it isn't installed. */
    data class Detection(val onDeviceAvailable: Boolean, val googleComponent: String?, val systemAvailable: Boolean) {
        val choice: Choice get() = choose(onDeviceAvailable, googleComponent != null, systemAvailable)
    }

    /** One line for the settings screen. Lists what's missing when nothing is available. */
    fun describe(detection: Detection): String = when (detection.choice) {
        Choice.ON_DEVICE -> "On-device recognizer (works offline)"
        Choice.GOOGLE -> "Google speech service"
        Choice.SYSTEM -> "Phone's default speech service"
        Choice.NONE -> buildString {
            append("Not found. Missing: ")
            val missing = buildList {
                if (!detection.onDeviceAvailable) add("on-device recognizer")
                if (detection.googleComponent == null) add("Google speech service")
                if (!detection.systemAvailable) add("default speech service")
            }
            append(missing.joinToString(", "))
        }
    }

    const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"
}
