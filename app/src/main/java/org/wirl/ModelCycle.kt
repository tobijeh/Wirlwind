package org.wirl

/**
 * Quick switch: the models the user has turned on in Settings › Dictation, which a vertical swipe on the recording bubble
 * moves between. Free of Android so it can be tested.
 *
 * - [active] is what's turned on. [current] is the one dictation uses now, and is always counted as on.
 * - Only downloaded models join the swipe cycle (plus the current one, which may be waiting for its download and uses
 *   Google's recognizer meanwhile), in picker order.
 */
object ModelCycle {
    /** The models a swipe moves between, in picker order. */
    fun members(active: Set<WhisperModel>, current: WhisperModel, ready: (WhisperModel) -> Boolean): List<WhisperModel> =
        WhisperModel.entries.filter { it == current || (it in active && ready(it)) }

    /** Whether a swipe has anywhere to go. */
    fun canSwitch(active: Set<WhisperModel>, current: WhisperModel, ready: (WhisperModel) -> Boolean): Boolean =
        members(active, current, ready).size > 1

    /** The model [step] places along the cycle from [current] (1 is the next one, -1 the previous one), wrapping round. */
    fun step(active: Set<WhisperModel>, current: WhisperModel, ready: (WhisperModel) -> Boolean, step: Int): WhisperModel {
        val list = members(active, current, ready)
        val i = list.indexOf(current)
        return list[(i + step).mod(list.size)]
    }

    /** What turning a model on or off in Settings leads to. */
    data class Choice(val active: Set<WhisperModel>, val current: WhisperModel)

    /**
     * Turns [model] on or off. Turning on never changes the current model. Turning off the current model moves dictation to
     * the next model that's still on (downloaded ones first). Returns null when [model] is the only one on, because one
     * must always stay on.
     */
    fun toggle(
        active: Set<WhisperModel>,
        current: WhisperModel,
        ready: (WhisperModel) -> Boolean,
        model: WhisperModel,
        on: Boolean,
    ): Choice? {
        val all = active + current
        if (on) return Choice(all + model, current)
        val rest = all - model
        if (rest.isEmpty()) return null
        if (model != current) return Choice(rest, current)
        val next = rest.sortedBy { it.ordinal }.let { left -> left.firstOrNull(ready) ?: left.first() }
        return Choice(rest, next)
    }
}
