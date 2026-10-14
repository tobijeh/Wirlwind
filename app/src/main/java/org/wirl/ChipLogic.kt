package org.wirl

import kotlin.math.max

/** Keeps auto-scrolling off for a while after the user touches the chip, so they can read earlier text. */
class AutoScrollPolicy(private val pauseMs: Long = 3_000L) {
    private var pausedUntil = Long.MIN_VALUE

    fun touched(now: Long) {
        pausedUntil = now + pauseMs
    }

    fun allowsAutoScroll(now: Long): Boolean = now >= pausedUntil
}

/**
 * Where the transcript chip goes: beside the bubble, on the side that has room, and never over the bubble.
 * Tries the preferred side, then the other side, then above, then below.
 */
object ChipPlacement {
    data class Spot(val x: Int, val y: Int)

    fun place(
        bubbleLeft: Int,
        bubbleTop: Int,
        bubbleSize: Int,
        chipWidth: Int,
        chipHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        gap: Int,
        preferLeft: Boolean,
    ): Spot {
        val centerY = (bubbleTop + bubbleSize / 2 - chipHeight / 2).coerceIn(0, max(0, screenHeight - chipHeight))
        val centerX = (bubbleLeft + bubbleSize / 2 - chipWidth / 2).coerceIn(0, max(0, screenWidth - chipWidth))
        val left = Spot(bubbleLeft - gap - chipWidth, centerY)
        val right = Spot(bubbleLeft + bubbleSize + gap, centerY)
        val above = Spot(centerX, bubbleTop - gap - chipHeight)
        val below = Spot(centerX, bubbleTop + bubbleSize + gap)
        val sides = if (preferLeft) listOf(left, right) else listOf(right, left)

        val bubble = Rect(bubbleLeft, bubbleTop, bubbleLeft + bubbleSize, bubbleTop + bubbleSize)
        return (sides + listOf(above, below)).firstOrNull { spot ->
            spot.x in 0..(screenWidth - chipWidth) &&
                spot.y in 0..(screenHeight - chipHeight) &&
                !Rect(spot.x, spot.y, spot.x + chipWidth, spot.y + chipHeight).overlaps(bubble)
        } ?: Spot(left.x.coerceIn(0, max(0, screenWidth - chipWidth)), centerY)
    }

    private data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun overlaps(other: Rect): Boolean =
            left < other.right && other.left < right && top < other.bottom && other.top < bottom
    }
}
