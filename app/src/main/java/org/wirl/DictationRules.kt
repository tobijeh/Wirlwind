package org.wirl

/** Recordings too short to be dictation: a tap that was almost immediately stopped. */
object RecordingRules {
    /** Shorter than this and the recording is discarded without a message. */
    const val MIN_RECORDING_MS = 1_000L

    fun tooShort(durationMs: Long): Boolean = durationMs < MIN_RECORDING_MS
}

/** What the Quick Settings tile shows. */
enum class TileState { ON, OFF, NEEDS_SETUP }

/** Decides the tile's state from what the app already knows. Needs setup when either the service or the key is missing. */
object WirlTileLogic {
    fun stateFor(accessibilityOn: Boolean, apiKeySaved: Boolean, bubbleEnabled: Boolean): TileState = when {
        !accessibilityOn || !apiKeySaved -> TileState.NEEDS_SETUP
        bubbleEnabled -> TileState.ON
        else -> TileState.OFF
    }

    /** Tapping toggles Wirl on or off. Needs setup opens setup instead, so nothing is toggled. */
    fun afterTap(state: TileState, bubbleEnabled: Boolean): Boolean? = when (state) {
        TileState.NEEDS_SETUP -> null
        TileState.ON, TileState.OFF -> !bubbleEnabled
    }
}

/** Which way the slide-to-cancel track extends from the bubble. */
enum class SlideDirection { LEFT, RIGHT }

/**
 * Slide-to-cancel rules, free of Android so they can be tested. While recording, the bubble is the knob of a short track:
 * it moves only along that track, from its resting x toward the far end. Sliding past the threshold cancels.
 */
object SlideToCancel {
    /** Share of the track the knob must reach before releasing cancels. Past it, the track turns red. */
    const val ARM_FRACTION = 0.6f

    /** A flick this fast toward the far end (px/s) cancels too, once the knob has moved a little. */
    const val FLICK_PX_PER_SEC = 1500f

    /** A flick only counts after the knob has moved at least this share of the track. */
    const val MIN_FLICK_FRACTION = 0.1f

    /** The bubble can only be repositioned when it isn't recording. While recording, only the slide moves it. */
    fun repositionAllowed(recording: Boolean): Boolean = !recording

    /** The track extends left unless there isn't [reachPx] of room to the left of the bubble. */
    fun directionFor(restX: Int, reachPx: Int): SlideDirection =
        if (restX - reachPx >= 0) SlideDirection.LEFT else SlideDirection.RIGHT

    /** The knob's x, kept between its rest position and the far end of the track. */
    fun clampX(x: Int, restX: Int, travelPx: Int, direction: SlideDirection): Int {
        val (min, max) = bounds(restX, travelPx, direction)
        return x.coerceIn(min, max)
    }

    /** How far along the track the knob is: 0 at rest, 1 at the far end. */
    fun progress(x: Int, restX: Int, travelPx: Int, direction: SlideDirection): Float {
        if (travelPx <= 0) return 0f
        val moved = if (direction == SlideDirection.LEFT) restX - x else x - restX
        return (moved.toFloat() / travelPx).coerceIn(0f, 1f)
    }

    fun armed(progress: Float): Boolean = progress >= ARM_FRACTION

    /** Releasing cancels when armed, or on a fast flick toward the far end. [velocityTowardFarEnd] is px/s, positive toward it. */
    fun cancelsOnRelease(progress: Float, velocityTowardFarEnd: Float): Boolean =
        armed(progress) || (progress >= MIN_FLICK_FRACTION && velocityTowardFarEnd >= FLICK_PX_PER_SEC)

    private fun bounds(restX: Int, travelPx: Int, direction: SlideDirection): Pair<Int, Int> =
        if (direction == SlideDirection.LEFT) (restX - travelPx) to restX else restX to (restX + travelPx)
}
