package org.wirl

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/** Asks the phone what speech recognition it has, and creates the recognizer for the chosen engine. */
object SpeechEngineAndroid {
    fun detect(context: Context): SpeechEngine.Detection {
        val onDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        return SpeechEngine.Detection(
            onDeviceAvailable = onDevice,
            // Choosing a service by name needs Android 12+; earlier versions only get the default.
            googleComponent = if (Build.VERSION.SDK_INT >= 31) googleService(context) else null,
            systemAvailable = SpeechRecognizer.isRecognitionAvailable(context),
        )
    }

    /** Needs the <queries> entry in the manifest on Android 11+, or the phone hides other apps' services. */
    private fun googleService(context: Context): String? =
        context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { it.serviceInfo }
            .firstOrNull { it.packageName == SpeechEngine.GOOGLE_PACKAGE }
            ?.let { ComponentName(it.packageName, it.name).flattenToString() }

    fun create(context: Context, choice: SpeechEngine.Choice, googleComponent: String?): SpeechRecognizer? = when (choice) {
        // On-device recognition only exists from Android 12; detection never chooses it earlier.
        SpeechEngine.Choice.ON_DEVICE -> if (Build.VERSION.SDK_INT >= 31) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            null
        }
        SpeechEngine.Choice.GOOGLE -> googleComponent
            ?.let { ComponentName.unflattenFromString(it) }
            ?.let { SpeechRecognizer.createSpeechRecognizer(context, it) }
        SpeechEngine.Choice.SYSTEM -> SpeechRecognizer.createSpeechRecognizer(context)
        SpeechEngine.Choice.NONE -> null
    }
}
