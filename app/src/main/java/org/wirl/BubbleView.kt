package org.wirl

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/**
 * The round Wirl bubble. The face is the chosen icon, a full circle image. While recording, a white tint and a pulsing white
 * ring sit on top of it. While processing, a dark scrim and spinner sit on top. When done, a green scrim and check sit
 * on top. Used by the overlay and the settings preview.
 */
class BubbleView(context: Context) : FrameLayout(context) {

    enum class State { IDLE, RECORDING, BUSY, DONE }

    private val background = GradientDrawable().apply { shape = GradientDrawable.OVAL }

    // A pulsing white ring behind the bubble, shown while dictating.
    internal val ring = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke((3 * resources.displayMetrics.density).toInt(), ContextCompat.getColor(context, R.color.white))
        }
        visibility = View.GONE
    }

    // The chosen icon, as the whole bubble face.
    internal val face = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        contentDescription = context.getString(R.string.bubble_desc)
    }

    // A white tint over the face while recording.
    internal val tint = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.white), 90))
        }
        visibility = View.GONE
    }

    // A dark scrim while processing, or a green scrim when done. The color is set by setState.
    internal val scrim = View(context).apply { visibility = View.GONE }

    internal val check = ImageView(context).apply {
        setImageResource(R.drawable.ic_check)
        visibility = View.GONE
    }

    internal val spinner = ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.white))
        visibility = View.GONE
    }

    var diameterPx: Int = 0
        private set

    private var pulse: ValueAnimator? = null

    init {
        setBackground(background)
        background.setColor(ContextCompat.getColor(context, R.color.bubble_fill))
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipChildren = false
        elevation = 6 * resources.displayMetrics.density
        addView(ring)
        addView(face)
        addView(tint)
        addView(scrim)
        addView(check)
        addView(spinner)
        setIcon(R.drawable.bubble_icon_swirl)
        setState(State.IDLE)
    }

    /** Resizes the circle. The face and overlays fill it, and the check and spinner scale with it. */
    fun setDiameter(px: Int) {
        diameterPx = px
        layoutParams = (layoutParams ?: LayoutParams(px, px)).apply {
            width = px
            height = px
        }
        ring.layoutParams = LayoutParams(px, px)
        face.layoutParams = LayoutParams(px, px)
        tint.layoutParams = LayoutParams(px, px)
        scrim.layoutParams = LayoutParams(px, px)
        check.layoutParams = LayoutParams((px * 0.5f).toInt(), (px * 0.5f).toInt(), Gravity.CENTER)
        spinner.layoutParams = LayoutParams((px * 0.54f).toInt(), (px * 0.54f).toInt(), Gravity.CENTER)
        requestLayout()
    }

    /** Shows a different icon as the face. The size and state are kept. */
    fun setIcon(@DrawableRes icon: Int) {
        face.setImageResource(icon)
    }

    fun setState(state: State) {
        tint.visibility = if (state == State.RECORDING) View.VISIBLE else View.GONE
        val scrimColor = when (state) {
            State.DONE -> ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.bubble_green), 215)
            else -> ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.ink), 128)
        }
        scrim.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(scrimColor)
        }
        scrim.visibility = if (state == State.BUSY || state == State.DONE) View.VISIBLE else View.GONE
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
