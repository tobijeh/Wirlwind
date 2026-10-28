package org.wirl

import android.animation.ValueAnimator
import android.content.Context
import androidx.annotation.DrawableRes
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.core.content.ContextCompat

/**
 * The round Wirl bubble: purple circle with the white glyph. While recording it turns white with a purple glyph and a
 * white pulse. Used by the overlay and the settings preview.
 */
class BubbleView(context: Context) : FrameLayout(context) {

    enum class State { IDLE, RECORDING, BUSY, DONE }

    private val background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    // A pulsing ring behind the bubble, shown while dictating.
    private val ring = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke((3 * resources.displayMetrics.density).toInt(), ContextCompat.getColor(context, R.color.white))
        }
        visibility = View.GONE
    }
    internal val glyph = ImageView(context).apply {
        setImageResource(R.drawable.bubble_icon_swirl)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = context.getString(R.string.bubble_desc)
    }
    internal val check = ImageView(context).apply {
        setImageResource(R.drawable.ic_check)
        visibility = View.GONE
    }
    private val spinner = ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.white))
        visibility = View.GONE
    }

    var diameterPx: Int = 0
        private set

    private var pulse: ValueAnimator? = null

    init {
        setBackground(background)
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipChildren = false
        elevation = 6 * resources.displayMetrics.density
        addView(ring)
        addView(glyph)
        addView(check)
        addView(spinner)
        setState(State.IDLE)
    }

    /** Resizes the circle and scales its contents to match. */
    fun setDiameter(px: Int) {
        diameterPx = px
        layoutParams = (layoutParams ?: LayoutParams(px, px)).apply {
            width = px
            height = px
        }
        ring.layoutParams = LayoutParams(px, px)
        glyph.layoutParams = LayoutParams((px * 0.56f).toInt(), (px * 0.56f).toInt(), Gravity.CENTER)
        check.layoutParams = LayoutParams((px * 0.5f).toInt(), (px * 0.5f).toInt(), Gravity.CENTER)
        spinner.layoutParams = LayoutParams((px * 0.54f).toInt(), (px * 0.54f).toInt(), Gravity.CENTER)
        requestLayout()
    }

    /** Shows a different glyph. The size and state are kept, so it scales and animates like the swirl. */
    fun setIcon(@DrawableRes icon: Int) {
        glyph.setImageResource(icon)
    }

    fun setState(state: State) {
        val recording = state == State.RECORDING
        val color = when (state) {
            State.DONE -> R.color.bubble_green
            State.RECORDING -> R.color.white
            else -> R.color.brand
        }
        background.setColor(ContextCompat.getColor(context, color))
        // The white glyph becomes purple on the white bubble while recording.
        if (recording) {
            glyph.setColorFilter(ContextCompat.getColor(context, R.color.brand), PorterDuff.Mode.SRC_IN)
        } else {
            glyph.clearColorFilter()
        }
        glyph.visibility = if (state == State.BUSY || state == State.DONE) View.GONE else View.VISIBLE
        spinner.visibility = if (state == State.BUSY) View.VISIBLE else View.GONE
        check.visibility = if (state == State.DONE) View.VISIBLE else View.GONE
        if (state == State.RECORDING) startPulse() else stopPulse()
    }

    private fun startPulse() {
        if (pulse != null) return
        ring.visibility = View.VISIBLE
        pulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                val f = animator.animatedFraction
                ring.scaleX = 1f + 0.4f * f
                ring.scaleY = 1f + 0.4f * f
                ring.alpha = 0.8f * (1f - f)
            }
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        ring.visibility = View.GONE
        ring.scaleX = 1f
        ring.scaleY = 1f
        ring.alpha = 1f
    }
}
