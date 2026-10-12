package org.wirl

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import kotlin.math.hypot

/**
 * Shows a floating bubble (an accessibility overlay, so no overlay permission is needed) while an
 * editable field is focused. Tap: clean up the field's text with Claude. Long-press: undo.
 */
class BubbleService : AccessibilityService() {
    private companion object {
        const val TAG = "Wirl"
    }

    private enum class State { IDLE, BUSY, DONE }

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
        val show = prefs.bubbleEnabled && (state == State.BUSY || focusedEditable() != null)
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

    private fun AccessibilityNodeInfo.replaceText(newText: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        if (!performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        // Put the cursor at the end so you can keep dictating.
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newText.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newText.length)
        }
        performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Actions

    private fun onTap() {
        if (state == State.BUSY) return
        val node = focusedEditable() ?: return toast(getString(R.string.no_field))
        val original = node.realText()
        if (original.isBlank()) return toast(getString(R.string.no_text))

        setState(State.BUSY)
        job = scope.launch {
            try {
                val cleaned = ClaudeClient.clean(this@BubbleService, original)
                val target = node.takeIf { it.refresh() } ?: focusedEditable()
                when {
                    target == null -> fail(getString(R.string.no_field))
                    target.realText() != original -> fail(getString(R.string.text_changed))
                    cleaned == original -> succeed()
                    !target.replaceText(cleaned) -> fail(getString(R.string.replace_failed))
                    else -> {
                        undoOriginal = original
                        undoCleaned = cleaned
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

    private fun onLongPress() {
        circle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (state == State.BUSY) return
        val original = undoOriginal
        val node = focusedEditable()
        // Only undo into the same text we produced, so we never overwrite a different message.
        if (original == null || node == null || node.realText() != undoCleaned) {
            showLabel(getString(R.string.nothing_to_undo), 1500)
            return
        }
        if (node.replaceText(original)) {
            undoOriginal = null
            undoCleaned = null
            setState(State.IDLE)
            showLabel(getString(R.string.restored), 1500)
        } else {
            toast(getString(R.string.replace_failed))
        }
    }

    private fun succeed() {
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
        when (newState) {
            State.IDLE -> circle.setState(BubbleView.State.IDLE)
            State.BUSY -> circle.setState(BubbleView.State.BUSY)
            State.DONE -> circle.setState(BubbleView.State.DONE)
        }
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
            onLongPress()
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
            maxLines = 2
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
