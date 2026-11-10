package org.wirl

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * The looping demo of quick switch on the Dictation page: a recording bubble swipes up, then down, and shows each model's
 * label (EN, EN+, 粵, 普) as the real bubble does. Runs only while it's on screen, and stays still when the phone has
 * animations turned off.
 */
class QuickSwitchDemo @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private fun dp(v: Float) = v * resources.displayMetrics.density

    internal val bubble = BubbleView(context)
    internal val up = arrow("▲")
    internal val down = arrow("▼")

    /** The labels the demo switches between: the user's ticked models when there are two or more, or EN and 粵. */
    var tags: List<String> = DEFAULT_TAGS
        set(value) {
            val next = value.takeIf { it.size >= 2 } ?: DEFAULT_TAGS
            if (next == field) return
            field = next
            position = 0
        }

    private var position = 0
    private var swipeUp = true

    private val step = object : Runnable {
        override fun run() {
            if (isShown) swipe()
            postDelayed(this, STEP_MS)
        }
    }

    init {
        clipChildren = false
        contentDescription = context.getString(R.string.quick_switch_demo_desc)
        addView(up, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
            topMargin = dp(4f).roundToInt()
        })
        addView(down, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply {
            bottomMargin = dp(4f).roundToInt()
        })
        val size = dp(BUBBLE_DP).roundToInt()
        addView(bubble, LayoutParams(size, size, Gravity.CENTER))
        bubble.setDiameter(size)
        bubble.setState(BubbleView.State.RECORDING)
        bubble.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun arrow(symbol: String) = TextView(context).apply {
        text = symbol
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(ContextCompat.getColor(context, R.color.settings_text_secondary))
        alpha = RESTING_ALPHA
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** One swipe: the bubble moves toward the arrow, springs back, and shows the label it switched to. */
    internal fun swipe() {
        val goingUp = swipeUp
        swipeUp = !swipeUp
        val arrow = if (goingUp) up else down
        position = (position + if (goingUp) 1 else -1).mod(tags.size)
        val label = tags[position]
        arrow.animate().alpha(1f).setDuration(150).start()
        bubble.animate().translationY(if (goingUp) -dp(TRAVEL_DP) else dp(TRAVEL_DP)).setDuration(260).withEndAction {
            bubble.flashTag(label)
            bubble.animate().translationY(0f).setDuration(220).start()
            arrow.animate().alpha(RESTING_ALPHA).setDuration(400).start()
        }.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(step)
        if (ValueAnimator.areAnimatorsEnabled()) postDelayed(step, FIRST_STEP_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(step)
        super.onDetachedFromWindow()
    }

    companion object {
        val DEFAULT_TAGS = listOf(WhisperModel.FAST.tag, WhisperModel.CANTONESE.tag)
        const val BUBBLE_DP = 48f
        const val TRAVEL_DP = 22f
        const val RESTING_ALPHA = 0.35f
        const val FIRST_STEP_MS = 800L
        const val STEP_MS = 2400L
    }
}
