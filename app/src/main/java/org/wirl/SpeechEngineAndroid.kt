package org.wirl

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/** Asks the phone what speech recognition it has, and creates the recognizer for an engine. */
object SpeechEngineAndroid {
    fun detect(context: Context): SpeechEngine.Detection = SpeechEngine.Detection(
        onDeviceAvailable = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context),
        // Naming a service needs Android 12+; earlier versions only get the default.
        googleAppComponent = if (Build.VERSION.SDK_INT >= 31) serviceFor(context, SpeechEngine.GOOGLE_APP_PACKAGE) else null,
        googleTtsComponent = if (Build.VERSION.SDK_INT >= 31) serviceFor(context, SpeechEngine.GOOGLE_TTS_PACKAGE) else null,
        systemAvailable = SpeechRecognizer.isRecognitionAvailable(context),
    )

    /** Needs the <queries> entry in the manifest on Android 11+, or the phone hides other apps' services. */
    private fun serviceFor(context: Context, packageName: String): String? =
        context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { it.serviceInfo }
            .firstOrNull { it.packageName == packageName }
            ?.let { ComponentName(it.packageName, it.name).flattenToString() }

    /** A recognizer for [choice], or null if this phone can't create it. */
    fun create(context: Context, choice: SpeechEngine.Choice, detection: SpeechEngine.Detection): SpeechRecognizer? =
        when (choice) {
            // On-device recognition only exists from Android 12.
            SpeechEngine.Choice.ON_DEVICE -> if (Build.VERSION.SDK_INT >= 31) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                null
            }
            SpeechEngine.Choice.GOOGLE_APP -> component(detection.googleAppComponent)?.let { createNamed(context, it) }
            SpeechEngine.Choice.GOOGLE_TTS -> component(detection.googleTtsComponent)?.let { createNamed(context, it) }
            SpeechEngine.Choice.SYSTEM -> SpeechRecognizer.createSpeechRecognizer(context)
            SpeechEngine.Choice.NONE -> null
        }

    private fun component(flattened: String?): ComponentName? = flattened?.let { ComponentName.unflattenFromString(it) }

    private fun createNamed(context: Context, name: ComponentName): SpeechRecognizer? =
        if (Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createSpeechRecognizer(context, name) else null
}
