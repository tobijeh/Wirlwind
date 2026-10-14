package org.wirl

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
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
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.hypot

/**
 * Shows a floating bubble (an accessibility overlay, so no overlay permission is needed) while an
 * editable field is focused. Tap: clean up the field's text with Claude. Long-press: undo.
 */
class BubbleService : AccessibilityService() {
    private companion object {
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
    private lateinit var labelParams: WindowManager.LayoutParams
    private var labelShown = false

    private var state = State.IDLE
    private var job: Job? = null

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
            Prefs.KEY_BUBBLE_ENABLED -> scheduleCheck(0)
            Prefs.KEY_BUBBLE_SIZE, Prefs.KEY_BUBBLE_OPACITY -> applyAppearance()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Service lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        wm = getSystemService(WindowManager::class.java)
        buildBubble()
        buildLabel()
        prefs.plain.registerOnSharedPreferenceChangeListener(prefsListener)
        scheduleCheck(0)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (::bubbleRoot.isInitialized) scheduleCheck(150)
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
        recognizer?.destroy()
        recognizer = null
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

    /** The focused editable field in another app, or null. Password fields are never touched. */
    private fun focusedEditable(): AccessibilityNodeInfo? {
        val node = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull() ?: return null
        if (!node.isEditable || node.isPassword || node.packageName == packageName) return null
        return node
    }

    private fun AccessibilityNodeInfo.realText(): String =
        if (isShowingHintText) "" else text?.toString().orEmpty()

    // ---------------------------------------------------------------------------------------------
    // Actions: tap starts or stops dictation; hold cleans up the field, or undoes the last cleanup.

    private val gestures = GestureMachine()
    private var recognizer: SpeechRecognizer? = null
    private var session: DictationSession? = null
    private var dictationField: FieldKey? = null

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
        if (!node.setText(original, original.length)) return toast(getString(R.string.replace_failed))
        if (prefs.showDoneMessage) showLabel(getString(R.string.undone), 1500)
        scheduleCheck(0)
    }

    private fun cleanUp(node: AccessibilityNodeInfo, key: FieldKey) {
        val original = node.realText()
        if (original.isBlank()) {
            gestures.finish()
            return toast(getString(R.string.no_text))
        }

        setState(State.BUSY)
        job = scope.launch {
            try {
                val cleaned = ClaudeClient.clean(this@BubbleService, original)
                val target = node.takeIf { it.refresh() } ?: focusedEditable()
                when {
                    target == null -> fail(getString(R.string.no_field))
                    target.realText() != original -> fail(getString(R.string.text_changed))
                    cleaned == original -> succeed()
                    !target.setText(cleaned, cleaned.length) -> fail(getString(R.string.replace_failed))
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

    private fun startDictation() {
        val node = focusedEditable()
        val key = node?.let { fieldKey(it) }
        val engine = SpeechEngineAndroid.detect(this)
        when {
            key == null -> return fail(getString(R.string.no_field))
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                return fail(DictationError.NO_PERMISSION.message)
            engine.choice == SpeechEngine.Choice.NONE -> {
                // The settings screen explains what's missing and has a button to the voice settings.
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return fail(DictationError.RECOGNIZER_UNAVAILABLE.message)
            }
        }

        dictationField = key
        session = DictationSession()
        recognizer = SpeechEngineAndroid.create(this, engine.choice, engine.googleComponent)?.also {
            it.setRecognitionListener(recognitionListener)
        }
            ?: return fail(DictationError.RECOGNIZER_UNAVAILABLE.message)
        // Android stops background microphone use unless a microphone foreground service is running.
        runCatching { ContextCompat.startForegroundService(this, Intent(this, DictationService::class.java)) }
        setState(State.RECORDING)
        showLabel(getString(R.string.listening), null)
        listen()
    }

    private fun stopDictation() {
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

    private fun listen() {
        val language = prefs.dictationLanguage.ifBlank { Locale.getDefault().toLanguageTag() }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        if (Build.VERSION.SDK_INT >= 33) {
            // Biasing: nudge the recognizer toward the dictionary's correct spellings.
            val terms = DictionaryRepository.get(this).entries.value.map { it.term }.distinct()
            if (terms.isNotEmpty()) intent.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(terms))
        }
        recognizer?.startListening(intent)
        session?.onListening()
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onPartialResults(partial: Bundle?) = onRecognizerEvent(RecognizerEvent.Partial(bestResult(partial)))
        override fun onResults(results: Bundle?) = onRecognizerEvent(RecognizerEvent.Results(bestResult(results)))
        override fun onError(error: Int) = onRecognizerEvent(RecognizerEvent.Error(error))
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun bestResult(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun onRecognizerEvent(event: RecognizerEvent) {
        val current = session ?: return
        when (val action = current.onEvent(event)) {
            null -> showLabel(current.transcript.ifBlank { getString(R.string.listening) }, null)
            SessionAction.Restart -> {
                showLabel(current.transcript.ifBlank { getString(R.string.listening) }, null)
                handler.postDelayed({ if (session === current) listen() }, 100)
            }
            is SessionAction.Finish -> endDictation(action)
        }
    }

    private fun endDictation(finish: SessionAction.Finish) {
        val field = dictationField
        recognizer?.destroy()
        recognizer = null
        session = null
        dictationField = null
        runCatching { stopService(Intent(this, DictationService::class.java)) }

        val raw = finish.transcript
        if (raw.isBlank()) return fail(finish.error?.message ?: DictationError.NO_SPEECH.message)

        setState(State.BUSY)
        hideLabel()
        job = scope.launch {
            val aiEnabled = prefs.dictationCleanup
            val cleaned = if (aiEnabled) {
                try {
                    ClaudeClient.clean(this@BubbleService, raw)
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
            val inserted = insertDictation(field, DictationResult.choose(raw, aiEnabled, cleaned))
            gestures.finish()
            val note = when {
                !inserted -> getString(R.string.copied_paste)
                finish.error != null -> finish.error.message
                aiEnabled && cleaned == null -> getString(R.string.cleanup_failed_raw)
                else -> null
            }
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

    /**
     * Puts the text into the field the dictation started in, at the cursor. If that field can't be
     * edited any more, copies the text to the clipboard instead. Returns whether it went into the field.
     */
    private fun insertDictation(field: FieldKey?, text: String): Boolean {
        val node = focusedEditable()
        val sameField = node != null && field != null &&
            node.packageName?.toString() == field.packageName &&
            node.viewIdResourceName == field.viewId
        val placement = DictationPlacement.decide(node != null, sameField, node?.isEditable == true)
        if (placement == DictationPlacement.CLIPBOARD || node == null) {
            copyToClipboard(text)
            return false
        }
        val plan = TextInsertion.plan(node.realText(), node.textSelectionStart, node.textSelectionEnd, text)
        if (node.setText(plan.text, plan.cursor)) return true
        copyToClipboard(text)
        return false
    }

    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Dictation", text))
    }

    // --- Results and state -----------------------------------------------------------------------

    private fun fieldKey(node: AccessibilityNodeInfo): FieldKey {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return FieldKey(node.packageName?.toString().orEmpty(), node.viewIdResourceName.orEmpty(), bounds.left, bounds.top)
    }

    /** Sets the field's whole text and puts the cursor at [cursor]. */
    private fun AccessibilityNodeInfo.setText(text: String, cursor: Int): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        }
        performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        return true
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
        private var dragging = false
        private var longFired = false
        private val longPress = Runnable {
            longFired = true
            onHold()
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    dragging = false
                    longFired = false
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && !longFired && hypot(dx, dy) > slop) {
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
                    if (dragging) {
                        prefs.bubblePosition = bubbleParams.x to bubbleParams.y
                    } else if (!longFired && e.actionMasked == MotionEvent.ACTION_UP) {
                        onTap()
                    }
                }
            }
            return true
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
        labelParams = overlayParams(touchable = false)
    }

    /** Shows [text] beside the bubble, on whichever side has room. Null duration = until hidden. */
    private fun showLabel(text: String, durationMs: Long?) {
        handler.removeCallbacks(hideLabelRunnable)
        if (!bubbleShown) return
        label.text = text
        label.maxWidth = (screenWidth() * 0.6f).toInt()
        label.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val w = label.measuredWidth
        val h = label.measuredHeight
        val circleLeft = bubbleParams.x + shadowPadPx
        val circleTop = bubbleParams.y + shadowPadPx
        val gap = dp(8)
        val onLeft = circleLeft + bubbleSizePx / 2 > screenWidth() / 2
        labelParams.x = if (onLeft) circleLeft - gap - w else circleLeft + bubbleSizePx + gap
        labelParams.x = labelParams.x.coerceIn(0, (screenWidth() - w).coerceAtLeast(0))
        labelParams.y = circleTop + bubbleSizePx / 2 - h / 2
        labelParams.width = ViewGroup.LayoutParams.WRAP_CONTENT

        if (labelShown) {
            wm.updateViewLayout(label, labelParams)
        } else {
            runCatching { wm.addView(label, labelParams) }.onSuccess { labelShown = true }
        }
        if (durationMs != null) handler.postDelayed(hideLabelRunnable, durationMs)
    }

    private fun hideLabel() {
        handler.removeCallbacks(hideLabelRunnable)
        if (!labelShown) return
        runCatching { wm.removeView(label) }
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
