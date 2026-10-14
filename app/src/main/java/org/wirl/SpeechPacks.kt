package org.wirl

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/** Checks and downloads offline speech packs. Needs Android 14 to check, and Android 13 to download. */
object SpeechPacks {
    private var pending: SpeechRecognizer? = null

    fun intentFor(language: String): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)

    /** Reports the pack status on the main thread. */
    fun check(context: Context, language: String, onResult: (DictationLanguage.PackStatus) -> Unit) {
        if (Build.VERSION.SDK_INT < 34 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            onResult(DictationLanguage.packStatus(language, null))
            return
        }
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer.checkRecognitionSupport(
            intentFor(language),
            ContextCompat.getMainExecutor(context),
            object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    onResult(DictationLanguage.packStatus(language, support.installedOnDeviceLanguages))
                    recognizer.destroy()
                }

                override fun onError(error: Int) {
                    onResult(DictationLanguage.packStatus(language, null))
                    recognizer.destroy()
                }
            },
        )
    }

    /** Asks the phone to download the pack. Returns false if this Android version can't do it. */
    fun download(context: Context, language: String): Boolean {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return false
        // The download is handled by the system, so the recognizer is kept until the next request.
        pending?.destroy()
        pending = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also {
            it.triggerModelDownload(intentFor(language))
        }
        return true
    }
}
