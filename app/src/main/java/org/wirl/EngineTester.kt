package org.wirl

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.View

/**
 * Settings → Test dictation. Starts each available recognizer in turn, in the same order dictation uses, and
 * reports whether it started. Every result goes to Diagnostics.
 */
class EngineTester(private val context: Context, private val report: (List<String>) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val results = mutableListOf<String>()
    private var recognizer: SpeechRecognizer? = null
    private var timeout: Runnable? = null

    fun run(language: String) {
        val detection = SpeechEngineAndroid.detect(context)
        val engines = SpeechEngine.installed(detection)
        if (engines.isEmpty()) {
            report(listOf("No speech engines found on this phone."))
            return
        }
        results.clear()
        testNext(engines, 0, detection, LanguageTag.format(language))
    }

    /** Cancels a test in progress, for example when the screen closes. */
    fun cancel() {
        timeout?.let { handler.removeCallbacks(it) }
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    private fun testNext(engines: List<SpeechEngine.Choice>, index: Int, detection: SpeechEngine.Detection, language: String) {
        if (index >= engines.size) {
            report(results.toList())
            return
        }
        val choice = engines[index]
        val created = runCatching { SpeechEngineAndroid.create(context, choice, detection) }.getOrNull()
        if (created == null) {
            finishWith(choice, language, "couldn't be created", null, null)
            return testNext(engines, index + 1, detection, language)
        }
        recognizer = created

        var done = false
        fun finish(outcome: String, code: Int?, throwable: Throwable?) {
            if (done) return
            done = true
            timeout?.let { handler.removeCallbacks(it) }
            runCatching { created.cancel() }
            runCatching { created.destroy() }
            recognizer = null
            finishWith(choice, language, outcome, code, throwable)
            testNext(engines, index + 1, detection, language)
        }

        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = finish("works", null, null)
            override fun onError(error: Int) = finish(
                "error $error: ${DictationError.messageFor(DictationError.from(error), error)}",
                error,
                null,
            )
            override fun onBeginningOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onResults(results: Bundle?) = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
        })

        val waited = Runnable { finish("no response within 5 seconds", null, null) }
        timeout = waited
        handler.postDelayed(waited, 5000)
        try {
            created.startListening(SpeechPacks.intentFor(language).putExtra(
                android.speech.RecognizerIntent.EXTRA_PREFER_OFFLINE,
                SpeechEngine.preferOffline(choice),
            ))
        } catch (e: Exception) {
            finish("failed to start: ${e.javaClass.simpleName}", null, e)
        }
    }

    private fun finishWith(choice: SpeechEngine.Choice, language: String, outcome: String, code: Int?, throwable: Throwable?) {
        results += "${choice.label} [$language]: $outcome"
        val prefs = Prefs(context)
        prefs.diagnostics = DiagnosticsLog.add(
            prefs.diagnostics,
            DiagnosticEntry(
                time = System.currentTimeMillis(),
                engines = "TEST ${choice.name} [$language]",
                errorCode = code,
                message = "Test: $outcome",
                stackTrace = throwable?.let { Log.getStackTraceString(it) },
            ),
        )
    }
}
