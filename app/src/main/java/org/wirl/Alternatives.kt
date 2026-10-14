package org.wirl

/** Recognizer alternatives: how many to keep, and which ones are worth sending. */
object Alternatives {
    const val MAX = 5
    const val MIN_CONFIDENCE = 0.3f

    /**
     * The top result is always kept. Other results are kept only if their confidence is at least [MIN_CONFIDENCE];
     * a missing score (negative) counts as unknown and is kept. At most [MAX] results.
     */
    fun filter(results: List<String>, scores: FloatArray?): List<String> {
        val cleaned = results.map { it.trim() }.filter { it.isNotEmpty() }
        return cleaned.take(MAX).filterIndexed { i, _ ->
            i == 0 || scores == null || i >= scores.size || scores[i] < 0f || scores[i] >= MIN_CONFIDENCE
        }
    }
}
