package org.wirl

import android.Manifest
import android.media.AudioFormat
import android.media.AudioManager
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.VelocityTracker
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Shows a floating bubble (an accessibility overlay, so no overlay permission is needed) while an
 * editable field is focused. Tap: clean up the field's text with Claude. Long-press: undo.
 */
class BubbleService : AccessibilityService() {
    private companion object {
        /** How long a pause may last before the recognizer decides the speaker has finished. */
        const val SILENCE_HINT_MS = 10_000L
        /** How far the knob slides from rest to the far end of the cancel track. */
        const val SLIDE_TRAVEL_DP = 150
        const val TAG = "Wirl"
    }

    private enum class State { IDLE, RECORDING, BUSY, DONE }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager

    private lateinit var bubbleRoot: FrameLayout
    private lateinit var circle: BubbleView
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var bubbleShown = false

    private lateinit var label: TextView
    private lateinit var chip: TranscriptChip
    private lateinit var labelRoot: FrameLayout
    private lateinit var labelParams: WindowManager.LayoutParams
    private var labelShown = false

    private var state = State.IDLE
    private var job: Job? = null
    /** The track the bubble slides along to cancel a recording. Shown only while recording. */
    private var slideTrack: TextView? = null
    private var slideShown = false
    private val slideTrackBackground = GradientDrawable()
    private var slideDirection = SlideDirection.LEFT
    /** The bubble's resting x while recording, and how far the knob can slide from it. */
    private var slideRestX = 0
    private var slideTravelPx = 0
    private var slideAnimator: ValueAnimator? = null

    /** Text before the last cleanup, and what we replaced it with (for undo). */
    private var undoOriginal: String? = null
    private var undoCleaned: String? = null

    private val bubbleSizePx get() = circle.diameterPx
    private val shadowPadPx by lazy { dp(8) }

    private val checkRunnable = Runnable { updateVisibility() }
    private val hideLabelRunnable = Runnable { hideLabel() }
    private val resetDoneRunnable = Runnable { if (state == State.DONE) setState(State.IDLE) }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.KEY_BUBBLE_ENABLED -> onBubbleEnabledChanged()
            Prefs.KEY_BUBBLE_SIZE, Prefs.KEY_BUBBLE_OPACITY, Prefs.KEY_BUBBLE_ICON -> applyAppearance()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Service lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        preloadWhisper()
        // A service killed mid-restart may have left a stream muted. Put it back before anything else.
        DictationSound.restoreLeftovers(AndroidStreamMuter(getSystemService(AudioManager::class.java)), prefs.mutedStreams)
            .forEach { Log.w(TAG, it) }
        wm = getSystemService(WindowManager::class.java)
        buildBubble()
        buildLabel()
        prefs.plain.registerOnSharedPreferenceChangeListener(prefsListener)
        scheduleCheck(0)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (::bubbleRoot.isInitialized && prefs.bubbleEnabled) scheduleCheck(150)
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::bubbleRoot.isInitialized) return
        hideLabel()
        clampBubble()
        if (bubbleShown) wm.updateViewLayout(bubbleRoot, bubbleParams)
    }

    override fun onDestroy() {
        releaseRecognizer()
        ownAudio.stop()
        endSound()
        whisper?.stop(System.currentTimeMillis()) {}
        whisperModels?.unload()
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        if (::prefs.isInitialized) prefs.plain.unregisterOnSharedPreferenceChangeListener(prefsListener)
        if (::bubbleRoot.isInitialized) {
            hideLabel()
            hideBubble()
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------------
    // Focus tracking

    private fun scheduleCheck(delayMs: Long) {
        handler.removeCallbacks(checkRunnable)
        handler.postDelayed(checkRunnable, delayMs)
    }

    private fun updateVisibility() {
        val show = prefs.bubbleEnabled && (state != State.IDLE || focusedEditable() != null)
        if (show) showBubble() else {
            hideLabel()
            hideBubble()
        }
    }

    /** Wirl was turned on or off, from the tile or Settings. Off stops everything; on starts again from a clean slate. */
    private fun onBubbleEnabledChanged() {
        if (!::bubbleRoot.isInitialized) return
        if (prefs.bubbleEnabled) restartBubble() else stopBubble()
        scheduleCheck(0)
    }

    /** Off: any recording or processing is discarded, the microphone and audio focus are released, and the bubble is hidden. */
    private fun stopBubble() {
        discardDictation("Wirl was turned off")
        hideLabel()
        hideBubble()
    }

    /** On: a new bubble, with every piece of state reset: recording, processing, undo, and overlays. */
    private fun restartBubble() {
        discardDictation("Wirl was turned on")
        gestures.reset()
        undoOriginal = null
        undoCleaned = null
        hideLabel()
        hideBubble()
        buildBubble()
        setState(State.IDLE)
    }

    /** The focused editable field in another app, or null. Password fields are never touched. */
    private fun focusedEditable(): AccessibilityNodeInfo? {
        val node = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull() ?: return null
        if (!node.isEditable || node.isPassword || node.packageName == packageName) return null
        return node
    }

    /** The text the user wrote. Hint text and blanks come back as "", so they're never cleaned, replaced, or sent. */
    private fun AccessibilityNodeInfo.realText(): String = FieldText.visible(
        text?.toString(),
        hintText?.toString(),
        isShowingHintText,
        textSelectionStart,
        textSelectionEnd,
        contentDescription?.toString(),
    )

    // ---------------------------------------------------------------------------------------------
    // Actions: tap starts or stops dictation; hold cleans up the field, or undoes the last cleanup.

    private val gestures = GestureMachine()
    private var recognizer: SpeechRecognizer? = null
    private var session: DictationSession? = null
    private var dictationField: FieldKey? = null
    private val ownAudio = OwnAudio()
    /** How many recognizer sessions have been given audio in this dictation. */
    private var sessionsOpened = 0
    /** Audio focus and the restart mute for the current dictation. Null when no dictation is running. */
    private var dictationSound: DictationSound? = null
    /** Sound lines from streams restored at the start of a dictation, reported with its Diagnostics entry. */
    private var pendingSoundLines: List<String> = emptyList()
    /** The Whisper dictation in progress, if this one uses Whisper. */
    private var whisper: WhisperSession? = null
    private var whisperStopping = false
    private var whisperModels: ModelHolder? = null
    private var whisperModelKey: Pair<WhisperModel, Int>? = null
    /** Whisper's timing for the dictation in progress, set when its text is ready. */
    private var whisperTiming: WhisperTiming? = null
    /** Engine, model, and chunk lines for the current dictation's Diagnostics entry. */
    private var dictationDetails = ""
    /** When the user tapped stop, for the stop-to-insert timing. */
    private var stopTappedAt = 0L
    /** Puts back the beep streams muted for a restart, shortly after, in case the recognizer's ready signal never came. */
    private val restoreRestartSound = Runnable { dictationSound?.restoreRestart() }

    private fun onTap() {
        when (gestures.onTap()) {
            GestureAction.StartRecording -> startDictation()
            GestureAction.StopRecording -> stopDictation()
            else -> Unit
        }
    }

    private fun onHold() {
        circle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val node = focusedEditable()
        if (node == null) {
            if (gestures.mode == GestureMachine.Mode.IDLE) toast(getString(R.string.no_field))
            return
        }
        val key = fieldKey(node)
        when (val action = gestures.onHold(key, node.realText())) {
            is GestureAction.Undo -> undo(node, action.original)
            GestureAction.Clean -> cleanUp(node, key)
            else -> Unit
        }
    }

    private fun undo(node: AccessibilityNodeInfo, original: String) {
        if (!node.setFieldText(original, original.length)) return toast(getString(R.string.replace_failed))
        if (prefs.showDoneMessage) showLabel(getString(R.string.undone), 1500)
        scheduleCheck(0)
    }

    private fun cleanUp(node: AccessibilityNodeInfo, key: FieldKey) {
        val original = node.realText()
        val placeholder = FieldText.isPlaceholder(node.text?.toString(), node.hintText?.toString(), node.contentDescription?.toString())
        // No valid cursor, or only a placeholder, means there's nothing the user wrote (realText is blank then).
        if (original.isBlank() || placeholder) {
            // Placeholder text is never sent to Claude.
            gestures.finish()
            return toast(getString(R.string.nothing_to_clean))
        }

        setState(State.BUSY)
        job = scope.launch {
            try {
                val cleaned = ClaudeClient.clean(this@BubbleService, original)
                val target = node.takeIf { it.refresh() } ?: focusedEditable()
                when {
                    cleaned.isBlank() -> {
                        // Nothing intended to keep: leave the field as it is.
                        gestures.finish()
                        setState(State.IDLE)
                        toast(getString(R.string.no_speech_heard))
                    }
                    target == null -> fail(getString(R.string.no_field))
                    target.realText() != original -> fail(getString(R.string.text_changed))
                    cleaned == original -> succeed()
                    !target.setFieldText(cleaned, cleaned.length) -> fail(getString(R.string.replace_failed))
                    else -> {
                        gestures.onCleaned(key, original, cleaned)
                        succeed()
                    }
                }
            } catch (e: CleanupException) {
                fail(e.message ?: "Error")
            } catch (e: Exception) {
                // Anything else would leave the bubble stuck on "Cleaning up…" and ignoring taps.
                Log.e(TAG, "Cleanup failed unexpectedly", e)
                fail("Cleanup failed: ${e.javaClass.simpleName}")
            }
        }
    }

    // --- Dictation -----------------------------------------------------------------------------
    // All SpeechRecognizer calls happen on the main thread. Only one recognizer exists at a time.

    private var detection: SpeechEngine.Detection? = null
    private var availableEngines: List<SpeechEngine.Choice> = emptyList()
    private var engine: SpeechEngine.Choice = SpeechEngine.Choice.NONE
    private val enginesTried = mutableSetOf<SpeechEngine.Choice>()
    private var dictationLanguageTag = ""

    /** The language tag for recognizers: BCP-47 with a hyphen, e.g. "en-US". */
    private fun dictationLanguage(): String =
        LanguageTag.format(DictationLanguage.resolve(prefs.dictationLanguage, Locale.getDefault().toLanguageTag()))
    private var busyRetries = 0
    private var attemptStart = 0L
    private val attemptTrail = mutableListOf<String>()

    private fun startDictation() {
        val node = focusedEditable()
        val key = node?.let { fieldKey(it) }
        val found = SpeechEngineAndroid.detect(this)
        dictationLanguageTag = dictationLanguage()
        val available = SpeechEngine.order(found, prefs.onDevicePackInstalled(dictationLanguageTag))
        val modelReady = WhisperApp.store(this).isReady(prefs.whisperModel.spec)
        val nativeLoads = WhisperLibrary.loads
        val use = EngineChoice.use(prefs.dictationEngine, modelReady, nativeLoads)
        val chosen = use.engine
        var note = fallbackNote(use.reason)
        if (use.reason == FallbackReason.MODEL_MISSING && !prefs.modelOfferShown) {
            // Offered once, without taking focus away from the field being dictated into. Settings has the download.
            prefs.modelOfferShown = true
            showLabel(getString(R.string.model_offer), 4000)
        }
        when {
            key == null -> return fail(getString(R.string.no_field))
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                return failWith(DictationError.NO_PERMISSION, null)
        }

        dictationField = key
        dictationDetails = ""
        stopTappedAt = 0L
        whisperTiming = null
        // Open the Claude connection and load the model while the user is still talking, so stop doesn't wait for them.
        ClaudeClient.warmUp()
        preloadWhisper()
        attemptStart = System.currentTimeMillis()
        attemptTrail.clear()
        try {
            // Android stops background microphone use unless a microphone foreground service is running.
            ContextCompat.startForegroundService(this, Intent(this, DictationService::class.java))
        } catch (e: Exception) {
            return failWith(DictationError.from(e), null, e)
        }
        setState(State.RECORDING)
        val audio = getSystemService(AudioManager::class.java)
        pendingSoundLines = DictationSound.restoreLeftovers(AndroidStreamMuter(audio), prefs.mutedStreams)
        dictationSound = DictationSound(
            enabled = true,
            focus = AndroidAudioFocus(audio),
            muter = AndroidStreamMuter(audio),
            store = prefs.mutedStreams,
            now = { System.currentTimeMillis() },
        )

        if (chosen == DictationEngine.WHISPER) {
            if (startWhisper(key)) return
            note = "Whisper couldn't open the microphone, using Google's recognizer"
        }
        // The engine in use here is Google's recognizer (Whisper returned above). Only it shows live words.
        if (DictationPreview.forEngine(DictationEngine.GOOGLE) == LivePreview.WORDS) showChip(getString(R.string.listening))

        // Google's recognizer, on its own or as the fallback.
        if (available.isEmpty()) {
            // The settings screen explains what's missing and has a button to the voice settings.
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return failWith(DictationError.RECOGNIZER_UNAVAILABLE, null)
        }
        dictationDetails = listOfNotNull("engine: google", note?.let { "note: $it" }).joinToString("\n")
        dictationSound?.begin()
        session = DictationSession(segmented = Build.VERSION.SDK_INT >= 33, ownAudio = Build.VERSION.SDK_INT >= 33)
        sessionsOpened = 0
        detection = found
        availableEngines = available
        enginesTried.clear()
        busyRetries = 0
        useEngine(available.first())
    }

    /** Why Google is used instead of Whisper, in words for Diagnostics. Null when there's no fallback. */
    private fun fallbackNote(reason: FallbackReason): String? = when (reason) {
        FallbackReason.MODEL_MISSING -> getString(R.string.engine_fallback_model, prefs.whisperModel.label)
        FallbackReason.NATIVE_MISSING -> getString(R.string.engine_fallback_native)
        FallbackReason.NONE -> null
    }

    /** Starts a Whisper dictation. Returns false if the microphone can't be opened, so the caller can use Google's recognizer. */
    private fun startWhisper(key: FieldKey): Boolean {
        val model = prefs.whisperModel
        val threads = prefs.whisperThreads
        val language = WhisperPrompt.languageFor(model, dictationLanguageTag)
        val prompt = WhisperPrompt.build(DictionaryRepository.get(this).entries.value.map { it.term })
        val created = WhisperSession(
            models = modelHolderFor(model, threads),
            language = language,
            prompt = prompt,
            now = { System.currentTimeMillis() },
        )
        if (!created.start()) {
            // Nothing was recorded, so there's nothing to transcribe. Stop the worker it started.
            created.stop(System.currentTimeMillis()) {}
            return false
        }
        whisper = created
        whisperStopping = false
        dictationDetails = listOf(
            "engine: whisper, model: ${model.label} (${model.fileName}), language: $language",
            "threads: $threads, initial prompt: ${if (prompt == null) "none" else "dictionary terms"}",
        ).joinToString("\n")
        DictationTones.play(DictationTones.START)
        dictationSound?.begin()
        return true
    }

    /** The model for [model], kept loaded between dictations. Switching models frees the previous one. */
    private fun modelHolderFor(model: WhisperModel, threads: Int): ModelHolder {
        val key = model to threads
        whisperModels?.takeIf { whisperModelKey == key }?.let { return it }
        whisperModels?.unload()
        val store = WhisperApp.store(this)
        val holder = ModelHolder(
            load = {
                WhisperTranscriber.load(store.fileFor(model.spec), threads) ?: run {
                    // A file that won't load is damaged. Remove it, so later dictations use Google until it's downloaded again.
                    Log.e(TAG, "Whisper model ${model.fileName} couldn't be loaded; deleting it")
                    store.delete(model.spec)
                    null
                }
            },
            now = { System.currentTimeMillis() },
            schedule = { delay, task -> handler.postDelayed(task, delay) },
        )
        whisperModels = holder
        whisperModelKey = key
        return holder
    }

    /** Loads the Whisper model in the background, so the first dictation doesn't wait for it. */
    private fun preloadWhisper() {
        if (prefs.dictationEngine != DictationEngine.WHISPER || !WhisperLibrary.loads) return
        val model = prefs.whisperModel
        if (!WhisperApp.store(this).isReady(model.spec)) return
        val holder = modelHolderFor(model, prefs.whisperThreads)
        Thread({ holder.get() }, "wirl-model-preload").start()
    }

    private fun stopDictation() {
        // A stray tap, under a second: discarded without processing or a message.
        if (RecordingRules.tooShort(System.currentTimeMillis() - attemptStart)) return discardDictation("recording under 1 second")
        whisper?.let { return stopWhisper(it) }
        stopTappedAt = System.currentTimeMillis()
        // Put the streams back first, so the stop sound plays.
        dictationSound?.restoreRestart()
        val current = session ?: return gestures.finish()
        val action = current.stopByUser()
        if (action is SessionAction.Finish) {
            endDictation(action)
        } else {
            // Wait for the recognizer's last result, which arrives through recognitionListener.
            setState(State.BUSY)
            recognizer?.stopListening()
        }
    }

    /** Creates the recognizer for [choice], replacing any existing one, and starts listening. */
    private fun useEngine(choice: SpeechEngine.Choice) {
        releaseRecognizer()
        engine = choice
        enginesTried += choice
        attemptTrail += "${choice.name} [$dictationLanguageTag]: started"
        val found = detection ?: return failWith(DictationError.RECOGNIZER_UNAVAILABLE, null)
        val created = try {
            SpeechEngineAndroid.create(this, choice, found)
        } catch (e: Exception) {
            return failWith(DictationError.from(e), null, e)
        }
        if (created == null) {
            attemptTrail += "$choice: couldn't create"
            return nextEngineOrFail(DictationError.RECOGNIZER_UNAVAILABLE, null)
        }
        recognizer = created.also { it.setRecognitionListener(recognitionListener) }
        listen()
    }

    private fun nextEngineOrFail(error: DictationError, code: Int?) {
        val next = availableEngines.firstOrNull { it !in enginesTried }
        if (next != null) useEngine(next) else failWith(error, code)
    }

    private fun listen() {
        val current = session
        val audioSource = if (current?.mode == DictationSession.Mode.RESTART_OWN_AUDIO) openAudioPipe() else null
        if (current?.mode == DictationSession.Mode.RESTART_OWN_AUDIO && audioSource == null) {
            current.useMicrophone("own audio unavailable, using the mic")
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, dictationLanguageTag)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Only the on-device recognizer works from the offline pack. Online recognizers must not insist on it.
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, SpeechEngine.preferOffline(engine))
            // Hints: wait a long time in silence before deciding the speaker has finished. Pauses between sentences
            // then don't end the recognizer.
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_HINT_MS)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_HINT_MS)
        if (Build.VERSION.SDK_INT >= 33) {
            // Biasing: nudge the recognizer toward the dictionary's correct spellings.
            val terms = DictionaryRepository.get(this).entries.value.map { it.term }.distinct()
            if (terms.isNotEmpty()) intent.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(terms))
            if (session?.mode == DictationSession.Mode.SEGMENTED) {
                intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, true)
            }
            audioSource?.let {
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, it)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, AudioFormat16k.SAMPLE_RATE)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            }
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            // Typically a SecurityException when the microphone can't be used in the background.
            Log.e(TAG, "startListening failed on $engine", e)
            return failWith(DictationError.from(e), null, e)
        } finally {
            // The recognizer has its own copy of the read side now.
            audioSource?.close()
        }
        session?.onListening()
    }

    /**
     * Opens the audio for the next recognizer session, from our own capture. The first session starts when it opens.
     * Each later one replays from just before the previous session ended, so speech across the gap isn't lost.
     */
    private fun openAudioPipe(): ParcelFileDescriptor? {
        val current = session ?: return null
        if (!ownAudio.isRunning && !ownAudio.start()) return null
        val endedAt = ownAudio.closePipe()
        current.noteGap(ownAudio.takeDroppedSamples() * 1000 / AudioFormat16k.SAMPLE_RATE)
        val from = if (sessionsOpened == 0) {
            endedAt
        } else {
            val plan = ReplayPlan.plan(endedAt, ownAudio.oldest)
            current.noteGap(plan.gapMs)
            current.beginReplay()
            plan.start
        }
        sessionsOpened++
        return ownAudio.openPipe(from)
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onPartialResults(partial: Bundle?) = onRecognizerEvent(RecognizerEvent.Partial(bestResult(partial)))
        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            val kept = Alternatives.filter(list, scores)
            onRecognizerEvent(RecognizerEvent.Results(kept.firstOrNull().orEmpty(), kept.drop(1)))
        }
        override fun onError(error: Int) = onRecognizerEvent(RecognizerEvent.Error(error))

        // Segmented sessions (Android 13+): each finished segment arrives here, then the session ends.
        override fun onSegmentResults(segmentResults: Bundle) {
            val list = segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val scores = segmentResults.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            val kept = Alternatives.filter(list, scores)
            onRecognizerEvent(RecognizerEvent.Segment(kept.firstOrNull().orEmpty(), kept.drop(1)))
        }
        override fun onEndOfSegmentedSession() = onRecognizerEvent(RecognizerEvent.EndOfSegments)
        override fun onReadyForSpeech(params: Bundle?) {
            // The restarted recognizer is listening, so its start beep has played. Put the streams back.
            dictationSound?.restoreRestart()
        }
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            // A pause ends the recognizer. If the user hasn't stopped, it restarts now and its stop beep plays.
            if (session?.stopping == false) silenceForRestart()
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun bestResult(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun onRecognizerEvent(event: RecognizerEvent) {
        val current = session ?: return
        if (event is RecognizerEvent.Error) {
            attemptTrail += "$engine: error ${event.code}"
            val plan = RecoveryPlanner.plan(
                code = event.code,
                transcriptEmpty = current.transcript.isEmpty(),
                current = engine,
                tried = enginesTried,
                available = availableEngines,
                busyRetries = busyRetries,
            )
            when (plan) {
                is Recovery.Fallback -> return useEngine(plan.engine)
                is Recovery.RecreateSame -> {
                    busyRetries++
                    releaseRecognizer()
                    // Give the busy recognizer a moment before making a new one.
                    handler.postDelayed({ if (session === current) useEngine(plan.engine) }, 400L * busyRetries)
                    return
                }
                Recovery.None -> Unit
            }
        }
        when (val action = current.onEvent(event)) {
            null -> showChip(current.transcript.ifBlank { getString(R.string.listening) })
            SessionAction.Restart -> {
                showChip(current.transcript.ifBlank { getString(R.string.listening) })
                silenceForRestart()
                handler.postDelayed({ if (session === current) listen() }, 100)
            }
            is SessionAction.Finish -> endDictation(action)
        }
    }

    /**
     * Stops recording and transcribes what's left. Transcription runs in the background, and the text goes through the same
     * cleanup and insertion as any other dictation once it's ready.
     */
    private fun stopWhisper(current: WhisperSession) {
        if (whisperStopping) return
        whisperStopping = true
        stopTappedAt = System.currentTimeMillis()
        DictationTones.play(DictationTones.END)
        setState(State.BUSY)
        current.stop(stopTappedAt) { outcome -> handler.post { finishWhisper(current, outcome) } }
    }

    private fun finishWhisper(current: WhisperSession, outcome: WhisperOutcome) {
        // Discarded while transcribing (turned off, or a new dictation started): this outcome is dropped.
        if (whisper !== current) return
        whisper = null
        runCatching { stopService(Intent(this, DictationService::class.java)) }
        dictationDetails = whisperLines(outcome)
        whisperTiming = outcome.timing()
        if (outcome.text.isBlank()) {
            val failure = outcome.chunks.firstNotNullOfOrNull { it.error }
            releaseRecognizer()
            ownAudio.stop()
            val soundLines = endSound()
            session = null
            dictationField = null
            val timing = DictationTiming(whisper = whisperTiming, stopToInsertMs = stopToInsertMs())
            if (failure != null) {
                val message = getString(R.string.whisper_failed, failure)
                recordAttempt(message, null, null, attemptDetails(timing, listOf(section("TRANSCRIPT", listOf("(empty)"))), soundLines))
                fail(message)
            } else {
                // Silence, noise, or only sound annotations: nothing was said, so Claude isn't called and nothing is inserted.
                recordAttempt(
                    DictationError.messageFor(DictationError.NO_SPEECH, null),
                    null,
                    null,
                    attemptDetails(timing, listOf(section("TRANSCRIPT", listOf("no words spoken"))), soundLines),
                )
                endQuietly()
            }
            return
        }
        endDictation(SessionAction.Finish(outcome.text, null, null, emptyList()))
    }

    private fun whisperLines(outcome: WhisperOutcome): String = buildString {
        appendLine(dictationDetails)
        val afterStop = outcome.chunks.size - outcome.duringRecording
        appendLine(
            "chunks: ${outcome.chunks.size} total, ${outcome.duringRecording} transcribed during recording, " +
                "$afterStop after stop, failed: ${outcome.chunks.count { it.error != null }}, missing: ${outcome.missing}",
        )
        for (chunk in outcome.chunks) {
            val error = chunk.error?.let { ", error: $it" }.orEmpty()
            val dropped = chunk.dropReason?.let { ", dropped: $it" }.orEmpty()
            appendLine("chunk ${chunk.seq}: ${chunk.transcribeMs} ms for ${chunk.samples} samples$error$dropped")
        }
        append("stop to text: ${outcome.stopToTextMs} ms")
        if (!outcome.finished) append("\nwarning: transcription didn't finish in time")
    }

    /** Milliseconds from the tap to stop until now, or null if stop wasn't tapped (Google fallback started it). */
    private fun stopToInsertMs(): Long? = if (stopTappedAt > 0) System.currentTimeMillis() - stopTappedAt else null

    /** One Diagnostics section: a title, then its lines indented. */
    private fun section(title: String, lines: List<String>): String =
        (listOf(title) + lines.filter { it.isNotBlank() }.map { "  $it" }).joinToString("\n")

    /** The full Diagnostics text for one attempt: timing first, then the sections, then the sound log. */
    private fun attemptDetails(timing: DictationTiming, sections: List<String>, soundLines: List<String>): String =
        (listOf(section("TIMING", timing.lines())) + sections + listOf(section("SOUND", soundLines.ifEmpty { listOf("none") })))
            .joinToString("\n\n")

    /** Mutes the beep streams for an automatic restart. They come back when the recognizer is ready, or shortly after. */
    private fun silenceForRestart() {
        dictationSound?.silenceRestart()
        handler.removeCallbacks(restoreRestartSound)
        handler.postDelayed(restoreRestartSound, 1500L)
    }

    /** Ends this dictation's sound: restores muted streams and releases audio focus. Returns the log for Diagnostics. */
    private fun endSound(): List<String> {
        val sound = dictationSound
        dictationSound = null
        handler.removeCallbacks(restoreRestartSound)
        sound?.end()
        return (pendingSoundLines + sound?.log.orEmpty()).also { pendingSoundLines = emptyList() }
    }


    private fun endDictation(finish: SessionAction.Finish) {
        val field = dictationField
        // Read before the session is cleared: which mode ran, and how many segments and restarts happened.
        val summary = session?.summary().orEmpty()
        releaseRecognizer()
        ownAudio.stop()
        val soundLines = endSound()
        session = null
        dictationField = null
        runCatching { stopService(Intent(this, DictationService::class.java)) }

        val raw = finish.transcript
        val spoken = SpeechGate.spokenWords(raw)
        if (raw.isBlank() && finish.error != null) {
            val message = DictationError.messageFor(finish.error, finish.errorCode)
            val timing = DictationTiming(stopToInsertMs = stopToInsertMs())
            recordAttempt(
                message,
                finish.errorCode,
                null,
                attemptDetails(timing, listOf(section("ENGINE", listOf(summary) + dictationDetails.lines()), section("TRANSCRIPT", listOf("(empty)"))), soundLines),
            )
            return fail(message)
        }
        if (!SpeechGate.callsClaude(spoken)) {
            // Silence, noise, or sound descriptions only: nothing goes to Claude and nothing is inserted.
            noSpeech(raw, summary, soundLines, "no words spoken: sound or silence only")
            return
        }

        setState(State.BUSY)
        hideLabel()
        job = scope.launch {
            // Same pipeline as hold-to-clean. The text is inserted only after Claude has answered.
            val aiEnabled = prefs.dictationCleanup
            var claude: ClaudeCallTiming? = null
            // Same cleanup as hold-to-clean, plus the recognizer's alternatives to resolve unclear words.
            val cleaned = if (aiEnabled) {
                runCatching { ClaudeClient.clean(this@BubbleService, spoken, finish.alternatives) { claude = it } }
            } else {
                null
            }
            // Cancelled while Claude was answering (turned off, or the bubble was reset): insert nothing.
            ensureActive()
            if (SpeechGate.textToInsert(spoken, aiEnabled, cleaned) == null) {
                // Claude replied with nothing, or only with a remark about the recording: nothing is inserted, and no message.
                val timing = DictationTiming(claude = claude, stopToInsertMs = stopToInsertMs())
                recordAttempt(
                    DictationError.messageFor(DictationError.NO_SPEECH, null),
                    null,
                    null,
                    attemptDetails(timing, listOf(section("ENGINE", listOf(summary) + dictationDetails.lines()), section("CLEANUP", listOf(cleanupReplyLine(cleaned)))), soundLines),
                )
                endQuietly()
                return@launch
            }
            val outcome = DictationDecision.decide(spoken, aiEnabled, cleaned)
            val insertStart = System.nanoTime()
            val insertion = insertDictation(field, outcome.text, spoken, (outcome as? DictationOutcome.Cleaned)?.text)
            val insertMs = (System.nanoTime() - insertStart) / 1_000_000
            val inserted = insertion.method != InsertMethod.FAILED
            gestures.finish()
            val note = when {
                !inserted -> getString(R.string.copied_paste)
                finish.error != null -> DictationError.messageFor(finish.error, finish.errorCode)
                outcome is DictationOutcome.Raw && outcome.reason != null ->
                    getString(R.string.raw_cleanup_failed, outcome.reason)
                else -> null
            }
            val cleanupLine = when {
                !aiEnabled -> "cleanup: off"
                outcome is DictationOutcome.Cleaned -> "cleanup: ok"
                else -> "cleanup: failed (${(outcome as DictationOutcome.Raw).reason})"
            }
            val timing = DictationTiming(whisper = whisperTiming, claude = claude, insertMs = insertMs, stopToInsertMs = stopToInsertMs())
            recordAttempt(
                note ?: "Inserted by ${insertion.method.name}",
                finish.errorCode,
                cleaned?.exceptionOrNull(),
                attemptDetails(
                    timing,
                    listOf(
                        section("ENGINE", listOf(summary) + dictationDetails.lines()),
                        section("CLEANUP", listOf(cleanupLine)),
                        section("INSERT", insertion.details.lines()),
                    ),
                    soundLines,
                ),
            )
            if (note != null) showLabel(note, 3000)
            if (inserted) {
                setState(State.DONE)
                handler.postDelayed(resetDoneRunnable, 1500)
            } else {
                setState(State.IDLE)
            }
            scheduleCheck(0)
        }
    }

    /** Nothing was said: logged to Diagnostics, with no message. Nothing is inserted. */
    private fun noSpeech(raw: String, summary: String, soundLines: List<String>, why: String) {
        val timing = DictationTiming(stopToInsertMs = stopToInsertMs())
        recordAttempt(
            DictationError.messageFor(DictationError.NO_SPEECH, null),
            null,
            null,
            attemptDetails(timing, listOf(section("ENGINE", listOf(summary) + dictationDetails.lines()), section("TRANSCRIPT", listOf(why, "raw: ${Visible.mark(raw)}"))), soundLines),
        )
        endQuietly()
    }

    /** Ends a dictation that inserts nothing, without a message. The bubble goes straight back to idle. */
    private fun endQuietly() {
        gestures.finish()
        setState(State.IDLE)
        scheduleCheck(0)
    }

    private fun cleanupReplyLine(cleaned: Result<String>?): String = when {
        cleaned?.getOrNull()?.let(SpeechGate::isRecordingRemark) == true ->
            "cleanup: reply was a remark about the recording, nothing inserted"
        else -> "cleanup: empty reply, nothing inserted"
    }

    /**
     * Stops whatever is recording or transcribing, throws it away, and releases the microphone, audio focus, and recognizer.
     * Nothing is processed or inserted. [reason] goes to Diagnostics when a dictation was running.
     */
    private fun discardDictation(reason: String) {
        job?.cancel()
        job = null
        val active = whisper != null || session != null || dictationField != null
        whisper?.cancel()
        whisper = null
        whisperStopping = false
        releaseRecognizer()
        ownAudio.stop()
        val soundLines = endSound()
        session = null
        dictationField = null
        runCatching { stopService(Intent(this, DictationService::class.java)) }
        if (active) {
            val timing = DictationTiming(stopToInsertMs = stopToInsertMs())
            recordAttempt("Discarded: $reason", null, null, attemptDetails(timing, listOf(section("TRANSCRIPT", listOf(reason))), soundLines))
        }
        gestures.reset()
        setState(State.IDLE)
    }

    /** Ends the attempt with an error: logs it to Diagnostics, cleans up, and tells the user why. */
    private fun failWith(error: DictationError, code: Int?, throwable: Throwable? = null) {
        val message = DictationError.messageFor(error, code)
        releaseRecognizer()
        ownAudio.stop()
        val soundLines = endSound()
        recordAttempt(message, code, throwable, attemptDetails(DictationTiming(), listOf(section("ENGINE", dictationDetails.lines())), soundLines))
        Log.e(TAG, "Dictation failed: $message (code $code)", throwable)
        session = null
        dictationField = null
        runCatching { stopService(Intent(this, DictationService::class.java)) }
        fail(message)
    }

    private fun recordAttempt(message: String, code: Int?, throwable: Throwable?, details: String = "") {
        val prefs = Prefs(this)
        val engines = attemptTrail.ifEmpty { enginesTried.map { it.name } }.joinToString(" → ")
        val entry = DiagnosticEntry(
            time = if (attemptStart == 0L) System.currentTimeMillis() else attemptStart,
            engines = engines,
            errorCode = code,
            message = message,
            stackTrace = throwable?.let { Log.getStackTraceString(it) },
            details = details,
        )
        prefs.diagnostics = DiagnosticsLog.add(prefs.diagnostics, entry)
    }

    /** Stops and destroys the current recognizer, if any. The next one is created by [useEngine]. */
    private fun releaseRecognizer() {
        handler.removeCallbacks(restoreRestartSound)
        dictationSound?.restoreRestart()
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    /** How dictated text went into the field, with each stage marked so whitespace shows, for Diagnostics. */
    private data class Insertion(val method: InsertMethod, val details: String)

    /**
     * Puts the dictated text in at the cursor by pasting, so the rest of the field is never rewritten. Only if pasting
     * fails is the whole field rewritten. If the field can't be edited, the text goes to the clipboard.
     */
    private fun insertDictation(field: FieldKey?, text: String, transcript: String, cleaned: String?): Insertion {
        val node = focusedEditable()
        val sameField = node != null && field != null &&
            node.packageName?.toString() == field.packageName &&
            node.viewIdResourceName == field.viewId
        val placement = DictationPlacement.decide(node != null, sameField, node?.isEditable == true)
        if (placement == DictationPlacement.CLIPBOARD || node == null) {
            copyToClipboard(text)
            return Insertion(
                InsertMethod.FAILED,
                stages(transcript, cleaned, text, "clipboard (field unavailable)", "", "", fieldLines(node), null),
            )
        }

        val leadingSpaceAllowed = FieldState.mayAddLeadingSpace(
            node.text?.toString(),
            node.hintText?.toString(),
            node.contentDescription?.toString(),
            node.isShowingHintText,
            node.textSelectionStart,
            node.textSelectionEnd,
        )
        val piece = TextInsertion.pieceFor(
            node.realText(),
            node.textSelectionStart,
            node.textSelectionEnd,
            text,
            allowLeadingSpace = leadingSpaceAllowed,
        )
        // Read the field before pasting, so the diagnostics show what it held going in.
        val fieldBefore = fieldLines(node)
        val method = DictationPaste.insert(AndroidClipboard(this), NodeField(node), piece) {
            val plan = TextInsertion.plan(node.realText(), node.textSelectionStart, node.textSelectionEnd, text)
            plan.text to plan.cursor
        }
        if (method == InsertMethod.FAILED) copyToClipboard(text)

        // Safety net: wherever no leading space was allowed, remove any leading whitespace that got in anyway.
        var guard = "none"
        node.refresh()
        val afterInsert = node.text?.toString().orEmpty()
        LeadingWhitespaceGuard.correct(leadingSpaceAllowed, afterInsert)?.let { fixed ->
            node.setFieldText(fixed, fixed.length)
            guard = "removed leading whitespace ${Visible.mark(afterInsert.takeWhile { it.isWhitespace() })}"
        }
        val leadingSpaceLine = if (piece.startsWith(" ")) {
            "leading space added: yes (text was before the cursor and not a space)"
        } else {
            "leading space added: no (${noLeadingSpaceReason(node, leadingSpaceAllowed)})"
        }
        val report = stages(transcript, cleaned, piece, method.name, guard, leadingSpaceLine, fieldBefore, afterInsert)
        return Insertion(method, report)
    }

    /** Why no leading space was added, for Diagnostics. */
    private fun noLeadingSpaceReason(node: AccessibilityNodeInfo, allowed: Boolean): String = when {
        allowed -> "no space needed: the text before the cursor ends in a space"
        !FieldText.hasCursor(node.textSelectionStart, node.textSelectionEnd) -> "no valid cursor (selection -1)"
        node.isShowingHintText -> "field is showing its hint"
        else -> "no text before the cursor"
    }

    /** The field's state as the accessibility tree reports it, with whitespace visible. */
    private fun fieldLines(node: AccessibilityNodeInfo?): String = buildString {
        if (node == null) {
            appendLine("field: not found")
        } else {
            appendLine("field before: getText ${Visible.mark(node.text?.toString())}, hintText ${Visible.mark(node.hintText?.toString())}")
            appendLine("field before: isShowingHintText ${node.isShowingHintText}, contentDescription ${Visible.mark(node.contentDescription?.toString())}")
            append("field before: selection ${node.textSelectionStart}..${node.textSelectionEnd}")
        }
    }

    /** Each stage of a dictation, with whitespace visible. */
    private fun stages(
        transcript: String,
        cleaned: String?,
        piece: String,
        method: String,
        guard: String,
        leadingSpaceLine: String,
        fieldBefore: String,
        afterInsert: String?,
    ): String = buildString {
        appendLine("raw transcript: ${Visible.mark(transcript)}")
        appendLine("cleaned output: ${cleaned?.let { Visible.mark(it) } ?: "not used"}")
        appendLine("placed for insert: ${Visible.mark(piece)}")
        if (leadingSpaceLine.isNotEmpty()) appendLine(leadingSpaceLine)
        appendLine(fieldBefore)
        afterInsert?.let { appendLine("field after insert: ${Visible.mark(it)}") }
        if (guard.isNotEmpty()) appendLine("leading-space guard: $guard")
        if (method.isNotEmpty()) append("method: $method")
    }.trimEnd()

    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Dictation", text))
    }

    // --- Results and state -----------------------------------------------------------------------

    private fun fieldKey(node: AccessibilityNodeInfo): FieldKey {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return FieldKey(node.packageName?.toString().orEmpty(), node.viewIdResourceName.orEmpty(), bounds.left, bounds.top)
    }

    private fun succeed() {
        gestures.finish()
        if (prefs.showDoneCheck) {
            setState(State.DONE)
            handler.postDelayed(resetDoneRunnable, 1500)
        } else {
            setState(State.IDLE)
        }
        if (prefs.showDoneMessage) showLabel(getString(R.string.cleaned_banner), 2500)
        prefs.lastResult = "Cleaned up at ${java.text.DateFormat.getTimeInstance().format(java.util.Date())}"
        scheduleCheck(0)
    }

    /** Shows the reason in the bubble's label (it stays visible), and records it for the settings screen. */
    private fun fail(message: String) {
        gestures.finish()
        setState(State.IDLE)
        Prefs(this).lastResult = "Failed: $message"
        showLabel(message, 4000)
        scheduleCheck(0)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun setState(newState: State) {
        state = newState
        handler.removeCallbacks(resetDoneRunnable)
        if (newState == State.RECORDING) showSlideTrack() else hideSlideTrack()
        circle.setState(
            when (newState) {
                State.IDLE -> BubbleView.State.IDLE
                State.RECORDING -> BubbleView.State.RECORDING
                State.BUSY -> BubbleView.State.BUSY
                State.DONE -> BubbleView.State.DONE
            },
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Bubble window

    private fun overlayParams(touchable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.LEFT }

    /**
     * Recording started: a pill slides out beside the bubble, and the bubble becomes its knob. The knob moves only along the
     * track. The track extends to the right when there isn't room on the left.
     */
    private fun showSlideTrack() {
        if (slideShown || !bubbleShown) return
        slideRestX = bubbleParams.x
        slideTravelPx = dp(SLIDE_TRAVEL_DP)
        val trackWidth = slideTravelPx + bubbleSizePx
        // The track's rest end sits under the knob's centre.
        val centreOffset = shadowPadPx + bubbleSizePx / 2
        slideDirection = SlideToCancel.directionFor(slideRestX + centreOffset, trackWidth)
        val track = slideTrack ?: buildSlideTrack().also { slideTrack = it }
        val left = if (slideDirection == SlideDirection.LEFT) slideRestX + centreOffset - trackWidth else slideRestX + centreOffset
        track.text = if (slideDirection == SlideDirection.LEFT) "‹  ${getString(R.string.slide_cancel)}" else "${getString(R.string.slide_cancel)}  ›"
        track.gravity = Gravity.CENTER_VERTICAL or if (slideDirection == SlideDirection.LEFT) Gravity.START else Gravity.END
        val knobSide = bubbleSizePx / 2 + dp(8)
        track.setPadding(
            if (slideDirection == SlideDirection.LEFT) dp(16) else knobSide,
            0,
            if (slideDirection == SlideDirection.LEFT) knobSide else dp(16),
            0,
        )
        slideTrackBackground.cornerRadius = bubbleSizePx / 2f
        setTrackArmed(false)
        track.pivotX = if (slideDirection == SlideDirection.LEFT) trackWidth.toFloat() else 0f
        track.scaleX = 0f
        val params = overlayParams(touchable = false).apply {
            width = trackWidth
            height = bubbleSizePx
            x = left
            y = bubbleParams.y + shadowPadPx
        }
        runCatching { wm.removeView(track) }
        runCatching { wm.addView(track, params) }.onSuccess {
            slideShown = true
            track.animate().scaleX(1f).setDuration(180).start()
            // Windows stack in the order they were added, so the bubble is re-added to stay on top of the track.
            if (bubbleShown) {
                runCatching { wm.removeView(bubbleRoot) }
                wm.addView(bubbleRoot, bubbleParams)
            }
        }
    }

    /** Recording ended: the track retracts, and the bubble is free to move again. */
    private fun hideSlideTrack() {
        val track = slideTrack ?: return
        if (!slideShown) return
        slideShown = false
        track.animate().scaleX(0f).setDuration(150).withEndAction {
            if (!slideShown) runCatching { wm.removeView(track) }
        }.start()
    }

    private fun buildSlideTrack(): TextView = TextView(this).apply {
        textSize = 15f
        background = slideTrackBackground
        contentDescription = getString(R.string.slide_cancel_desc)
    }

    /** Armed: the track turns red, to show that releasing will cancel. */
    private fun setTrackArmed(armed: Boolean) {
        slideTrackBackground.setColor(getColor(if (armed) R.color.bubble_record else R.color.brand_light))
        slideTrack?.setTextColor(getColor(if (armed) R.color.white else R.color.brand))
    }

    /** Ends a slide. A release past the threshold, or a fast flick, cancels. Either way the knob springs back to rest. */
    private fun endSlide(cancel: Boolean) {
        setTrackArmed(false)
        if (cancel) discardDictation("cancelled by sliding the bubble to Cancel")
        springBack()
    }

    /** Moves the knob back to its resting x, the same place a recording started from. */
    private fun springBack() {
        slideAnimator?.cancel()
        slideAnimator = ValueAnimator.ofInt(bubbleParams.x, slideRestX).apply {
            duration = 200
            addUpdateListener {
                bubbleParams.x = it.animatedValue as Int
                if (bubbleShown) wm.updateViewLayout(bubbleRoot, bubbleParams)
            }
            start()
        }
    }

    private fun buildBubble() {
        circle = BubbleView(this)
        bubbleRoot = FrameLayout(this).apply {
            // Padding leaves room for the soft shadow to render inside the window.
            setPadding(shadowPadPx, shadowPadPx, shadowPadPx, shadowPadPx)
            clipToPadding = false
            clipChildren = false
            addView(circle)
        }
        applyAppearance(update = false)
        bubbleParams = overlayParams(touchable = true)
        val saved = prefs.bubblePosition
        if (saved != null) {
            bubbleParams.x = saved.first
            bubbleParams.y = saved.second
        } else {
            bubbleParams.x = screenWidth() - windowSize() - dp(4)
            bubbleParams.y = (screenHeight() * 0.35f).toInt()
        }
        clampBubble()
        circle.setOnTouchListener(BubbleTouchListener())
    }

    /** Applies the size and opacity from settings. Called again whenever they change. */
    private fun applyAppearance(update: Boolean = true) {
        hideLabel()
        circle.setDiameter(dp(prefs.bubbleSizeDp))
        circle.setIcon(BubbleIcons.find(prefs.bubbleIcon).drawable)
        bubbleRoot.alpha = prefs.bubbleOpacity / 100f
        if (!update) return
        clampBubble()
        if (bubbleShown) wm.updateViewLayout(bubbleRoot, bubbleParams)
    }

    private fun windowSize() = bubbleSizePx + 2 * shadowPadPx

    private fun clampBubble() {
        bubbleParams.x = bubbleParams.x.coerceIn(0, (screenWidth() - windowSize()).coerceAtLeast(0))
        bubbleParams.y = bubbleParams.y.coerceIn(0, (screenHeight() - windowSize()).coerceAtLeast(0))
    }

    private fun showBubble() {
        if (bubbleShown) return
        clampBubble()
        runCatching { wm.addView(bubbleRoot, bubbleParams) }.onSuccess { bubbleShown = true }
    }

    private fun hideBubble() {
        hideSlideTrack()
        if (!bubbleShown) return
        runCatching { wm.removeView(bubbleRoot) }
        bubbleShown = false
    }

    private inner class BubbleTouchListener : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@BubbleService).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false
        private var dragging = false
        private var longFired = false
        private var sliding = false
        private var sliderArmed = false
        private var velocity: VelocityTracker? = null
        private val longPress = Runnable {
            longFired = true
            onHold()
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // A spring-back still running would move the bubble away from its rest position: stop it there.
                    if (slideAnimator?.isRunning == true) {
                        slideAnimator?.cancel()
                        bubbleParams.x = slideRestX
                        if (bubbleShown) wm.updateViewLayout(bubbleRoot, bubbleParams)
                    }
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                    downX = e.rawX
                    downY = e.rawY
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    moved = false
                    dragging = false
                    longFired = false
                    sliding = false
                    sliderArmed = false
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    velocity?.addMovement(e)
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) > slop) moved = true
                    if (!SlideToCancel.repositionAllowed(recording = state == State.RECORDING)) {
                        // Recording: the bubble holds still, except to slide along the cancel track.
                        if (!sliding && !longFired && abs(dx) > slop && abs(dx) > abs(dy) && slideShown) {
                            sliding = true
                            handler.removeCallbacks(longPress)
                            hideLabel()
                        }
                        if (sliding) slideTo(startX + dx.toInt())
                        return true
                    }
                    if (!dragging && !longFired && moved) {
                        dragging = true
                        handler.removeCallbacks(longPress)
                        hideLabel()
                    }
                    if (dragging) {
                        bubbleParams.x = startX + dx.toInt()
                        bubbleParams.y = startY + dy.toInt()
                        clampBubble()
                        wm.updateViewLayout(bubbleRoot, bubbleParams)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    if (sliding) {
                        velocity?.computeCurrentVelocity(1000)
                        val xVelocity = velocity?.xVelocity ?: 0f
                        val towardFarEnd = if (slideDirection == SlideDirection.LEFT) -xVelocity else xVelocity
                        val progress = SlideToCancel.progress(bubbleParams.x, slideRestX, slideTravelPx, slideDirection)
                        val cancel = e.actionMasked == MotionEvent.ACTION_UP &&
                            SlideToCancel.cancelsOnRelease(progress, towardFarEnd)
                        sliding = false
                        sliderArmed = false
                        endSlide(cancel)
                    } else if (dragging) {
                        prefs.bubblePosition = bubbleParams.x to bubbleParams.y
                    } else if (!moved && !longFired && e.actionMasked == MotionEvent.ACTION_UP) {
                        // A tap stops the recording and processes it, even while the track is shown.
                        onTap()
                    }
                    velocity?.recycle()
                    velocity = null
                }
            }
            return true
        }

        /** Moves the knob along the track, and buzzes once when it crosses the threshold. */
        private fun slideTo(x: Int) {
            bubbleParams.x = SlideToCancel.clampX(x, slideRestX, slideTravelPx, slideDirection)
            if (bubbleShown) wm.updateViewLayout(bubbleRoot, bubbleParams)
            val armedNow = SlideToCancel.armed(SlideToCancel.progress(bubbleParams.x, slideRestX, slideTravelPx, slideDirection))
            if (armedNow != sliderArmed) {
                sliderArmed = armedNow
                setTrackArmed(armedNow)
                circle.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Label / banner window

    private fun buildLabel() {
        label = TextView(this).apply {
            setBackgroundResource(R.drawable.label_bg)
            setTextColor(ContextCompat.getColor(context, R.color.white))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            maxLines = 3
        }
        chip = TranscriptChip(this)
        chip.visibility = View.GONE
        labelRoot = FrameLayout(this).apply {
            addView(label)
            addView(chip)
        }
        labelParams = overlayParams(touchable = false)
    }

    /** Shows a message beside the bubble. Null duration = until hidden. Messages don't take touches. */
    private fun showLabel(text: String, durationMs: Long?) {
        handler.removeCallbacks(hideLabelRunnable)
        if (!bubbleShown) return
        chip.visibility = View.GONE
        label.visibility = View.VISIBLE
        label.text = text
        label.maxWidth = (screenWidth() * 0.6f).toInt()
        label.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        labelParams.flags = overlayParams(touchable = false).flags
        labelParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
        labelParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        placeLabel(label.measuredWidth, label.measuredHeight)
        if (durationMs != null) handler.postDelayed(hideLabelRunnable, durationMs)
    }

    /**
     * The live transcript beside the bubble while dictating: three lines tall, width 60% of the screen, scrolled to the
     * newest words. It takes touches so the user can scroll back while dictating.
     */
    private fun showChip(transcript: String) {
        handler.removeCallbacks(hideLabelRunnable)
        if (!bubbleShown) return
        label.visibility = View.GONE
        chip.visibility = View.VISIBLE
        chip.show(transcript)
        val width = (screenWidth() * 0.6f).toInt()
        labelParams.flags = overlayParams(touchable = true).flags
        labelParams.width = width
        labelParams.height = chip.maxHeightPx
        chip.layoutParams = FrameLayout.LayoutParams(width, chip.maxHeightPx)
        placeLabel(width, chip.maxHeightPx)
    }

    /** Positions the label window beside the bubble, never over it. */
    private fun placeLabel(w: Int, h: Int) {
        val circleLeft = bubbleParams.x + shadowPadPx
        val circleTop = bubbleParams.y + shadowPadPx
        val spot = ChipPlacement.place(
            bubbleLeft = circleLeft,
            bubbleTop = circleTop,
            bubbleSize = bubbleSizePx,
            chipWidth = w,
            chipHeight = h,
            screenWidth = screenWidth(),
            screenHeight = screenHeight(),
            gap = dp(8),
            preferLeft = circleLeft + bubbleSizePx / 2 > screenWidth() / 2,
        )
        labelParams.x = spot.x
        labelParams.y = spot.y
        if (labelShown) {
            wm.updateViewLayout(labelRoot, labelParams)
        } else {
            runCatching { wm.addView(labelRoot, labelParams) }.onSuccess { labelShown = true }
        }
    }

    private fun hideLabel() {
        handler.removeCallbacks(hideLabelRunnable)
        if (!labelShown) return
        runCatching { wm.removeView(labelRoot) }
        labelShown = false
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics,
    ).toInt()

    private fun screenWidth() = resources.displayMetrics.widthPixels
    private fun screenHeight() = resources.displayMetrics.heightPixels
}
