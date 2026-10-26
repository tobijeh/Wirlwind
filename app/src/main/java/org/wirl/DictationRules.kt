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

/** The bottom-centre target that cancels a recording when the bubble is dragged onto it. */
data class CancelTarget(val centerX: Int, val centerY: Int, val radius: Int) {
    /** True when a point (the bubble's centre) is over the target. */
    fun contains(x: Int, y: Int): Boolean {
        val dx = (x - centerX).toLong()
        val dy = (y - centerY).toLong()
        return dx * dx + dy * dy <= radius.toLong() * radius
    }

    companion object {
        /** Centred horizontally, with its middle [bottomOffset] pixels above the bottom edge. */
        fun bottomCentre(screenWidth: Int, screenHeight: Int, radius: Int, bottomOffset: Int): CancelTarget =
            CancelTarget(screenWidth / 2, screenHeight - bottomOffset, radius)
    }
}
