package org.wirl

/**
 * The speech recognizers dictation can use. Dictation tries them in [order]: the on-device recognizer only when its
 * language pack is known to be installed, then the Google app, then Speech Services by Google, then the system default.
 */
object SpeechEngine {
    enum class Choice(val label: String) {
        ON_DEVICE("On-device recognizer"),
        GOOGLE_APP("Google app"),
        GOOGLE_TTS("Speech Services by Google"),
        SYSTEM("Phone's default speech service"),
        NONE("None"),
    }

    const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"
    const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"

    /** What the phone offers. The component fields are "package/class", or null if that service isn't installed. */
    data class Detection(
        val onDeviceAvailable: Boolean,
        val googleAppComponent: String?,
        val googleTtsComponent: String?,
        val systemAvailable: Boolean,
    )

    /**
     * Engines to try, in order. The on-device recognizer comes first only when [onDevicePackInstalled] is true,
     * because without its language pack it fails with a language error.
     */
    fun order(detection: Detection, onDevicePackInstalled: Boolean): List<Choice> = listOfNotNull(
        Choice.ON_DEVICE.takeIf { detection.onDeviceAvailable && onDevicePackInstalled },
        Choice.GOOGLE_APP.takeIf { detection.googleAppComponent != null },
        Choice.GOOGLE_TTS.takeIf { detection.googleTtsComponent != null },
        Choice.SYSTEM.takeIf { detection.systemAvailable },
    )

    /** Only the on-device recognizer works from its offline pack. Online recognizers must not insist on it. */
    fun preferOffline(choice: Choice): Boolean = choice == Choice.ON_DEVICE

    /** Every engine the phone has, including on-device even without its pack. Used by the test. */
    fun installed(detection: Detection): List<Choice> = order(detection, onDevicePackInstalled = detection.onDeviceAvailable)

    /** One line for the settings screen. Lists what's missing when nothing is available. */
    fun describe(detection: Detection, onDevicePackInstalled: Boolean): String {
        val first = order(detection, onDevicePackInstalled).firstOrNull()
            ?: return buildString {
                append("Not found. Missing: ")
                append(
                    buildList {
                        if (!detection.onDeviceAvailable) add("on-device recognizer")
                        if (detection.googleAppComponent == null) add("Google app")
                        if (detection.googleTtsComponent == null) add("Speech Services by Google")
                        if (!detection.systemAvailable) add("default speech service")
                    }.joinToString(", "),
                )
            }
        return first.label
    }
}
