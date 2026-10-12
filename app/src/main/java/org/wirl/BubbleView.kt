package org.wirl

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.core.content.ContextCompat

/** The round Wirl bubble: teal circle with the white glyph. Used by the overlay and the settings preview. */
class BubbleView(context: Context) : FrameLayout(context) {

    enum class State { IDLE, BUSY, DONE }

    private val background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private val glyph = ImageView(context).apply {
        setImageResource(R.drawable.ic_wirl_glyph)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = context.getString(R.string.bubble_desc)
    }
    private val check = ImageView(context).apply {
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

    init {
        setBackground(background)
        outlineProvider = ViewOutlineProvider.BACKGROUND
        elevation = 6 * resources.displayMetrics.density
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
        glyph.layoutParams = LayoutParams((px * 0.56f).toInt(), (px * 0.56f).toInt(), Gravity.CENTER)
        check.layoutParams = LayoutParams((px * 0.5f).toInt(), (px * 0.5f).toInt(), Gravity.CENTER)
        spinner.layoutParams = LayoutParams((px * 0.54f).toInt(), (px * 0.54f).toInt(), Gravity.CENTER)
        requestLayout()
    }

    fun setState(state: State) {
        val color = if (state == State.DONE) R.color.bubble_green else R.color.wirl_teal
        background.setColor(ContextCompat.getColor(context, color))
        glyph.visibility = if (state == State.IDLE) View.VISIBLE else View.GONE
        spinner.visibility = if (state == State.BUSY) View.VISIBLE else View.GONE
        check.visibility = if (state == State.DONE) View.VISIBLE else View.GONE
    }
}
