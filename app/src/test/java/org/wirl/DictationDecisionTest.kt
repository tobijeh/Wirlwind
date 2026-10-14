package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Raw text is inserted only when the API call fails (or cleanup is off). */
class DictationDecisionTest {
    @Test fun successfulCleanupInsertsTheCleanedText() {
        val outcome = DictationDecision.decide("um hi there", aiEnabled = true, cleaned = Result.success("Hi there."))
        assertEquals(DictationOutcome.Cleaned("Hi there."), outcome)
    }

    @Test fun rawTextIsInsertedOnlyWhenTheApiCallFails() {
        val failed = DictationDecision.decide("um hi there", aiEnabled = true, cleaned = Result.failure(CleanupException("Claude is unavailable, try again")))
        assertEquals(DictationOutcome.Raw("um hi there", "Claude is unavailable, try again"), failed)
    }

    @Test fun rawTextIsInsertedWithoutAReasonWhenCleanupIsOff() {
        assertEquals(DictationOutcome.Raw("um hi", null), DictationDecision.decide("um hi", aiEnabled = false, cleaned = null))
        assertEquals(
            DictationOutcome.Raw("um hi", null),
            DictationDecision.decide("um hi", aiEnabled = false, cleaned = Result.success("Hi.")),
        )
    }

    @Test fun blankCleanupCountsAsAFailure() {
        assertEquals(DictationOutcome.Raw("um hi", "empty response"), DictationDecision.decide("um hi", aiEnabled = true, cleaned = Result.success("  ")))
    }

    @Test fun unexpectedErrorsNameTheirType() {
        assertEquals("unexpected error (IllegalStateException)", DictationDecision.reasonFor(IllegalStateException("x")))
    }

    @Test fun cleanupCannotBeMissedWhenItFails() {
        val outcome = DictationDecision.decide("raw", aiEnabled = true, cleaned = Result.failure(CleanupException("Invalid API key")))
        assertEquals("Invalid API key", (outcome as DictationOutcome.Raw).reason)
        assertNull(DictationDecision.decide("raw", aiEnabled = true, cleaned = Result.success("Cleaned.")).let { (it as? DictationOutcome.Raw)?.reason })
    }
}
