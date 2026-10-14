package org.wirl

import android.content.Context
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * The live transcript beside the bubble while dictating. Three lines tall; the newest words stay at the bottom.
 * Touching it pauses auto-scrolling for a few seconds so earlier text can be read.
 */
class TranscriptChip(context: Context, private val clock: () -> Long = { SystemClock.uptimeMillis() }) : FrameLayout(context) {
    private val policy = AutoScrollPolicy()
    private val padding = dp(12)
    val scrollView = ScrollView(context)
    val textView = TextView(context)

    /** Height of three lines of text, plus padding. */
    val maxHeightPx: Int = 3 * textView.apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP) }.lineHeight + 2 * dp(6)

    init {
        textView.apply {
            setTextColor(ContextCompat.getColor(context, R.color.white))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
            setPadding(padding, dp(6), padding, dp(6))
            // Full text, no ellipsis: the scroll view handles what doesn't fit.
            maxLines = Int.MAX_VALUE
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop || top != oldTop) scrollToLatest()
            }
        }
        scrollView.apply {
            setBackgroundResource(R.drawable.label_bg)
            isVerticalScrollBarEnabled = false
            // A soft fade at the top edge hints that earlier words are scrolled out of view.
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(16))
            addView(textView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            setOnTouchListener { _, _ ->
                onUserTouch()
                false // still let the scroll view handle the drag
            }
        }
        addView(scrollView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Shows the transcript so far. Scrolls to the newest words unless the user recently touched the chip. */
    fun show(transcript: String) {
        if (textView.text.toString() != transcript) textView.text = transcript
    }

    /** The user touched the chip: pause auto-scrolling for a few seconds. */
    fun onUserTouch() {
        policy.touched(clock())
    }

    private fun scrollToLatest() {
        if (!policy.allowsAutoScroll(clock())) return
        val bottom = (textView.height - scrollView.height).coerceAtLeast(0)
        scrollView.scrollTo(0, bottom)
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()

    private companion object {
        const val TEXT_SP = 13f
    }
}
