package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Test
import org.wirl.SpeechEngine.Choice.GOOGLE
import org.wirl.SpeechEngine.Choice.ON_DEVICE
import org.wirl.SpeechEngine.Choice.SYSTEM

class RecoveryPlannerTest {
    private val all = listOf(ON_DEVICE, GOOGLE, SYSTEM)

    private fun plan(
        code: Int,
        current: SpeechEngine.Choice = ON_DEVICE,
        tried: Set<SpeechEngine.Choice> = setOf(ON_DEVICE),
        available: List<SpeechEngine.Choice> = all,
        transcriptEmpty: Boolean = true,
        busyRetries: Int = 0,
    ) = RecoveryPlanner.plan(code, transcriptEmpty, current, tried, available, busyRetries)

    @Test fun onDeviceFailureFallsBackToTheNextEngine() {
        // Code 13: the on-device language pack isn't downloaded. A non-language failure goes to Google first.
        assertEquals(Recovery.Fallback(SYSTEM), plan(13))
        assertEquals(Recovery.Fallback(GOOGLE), plan(5))
    }

    @Test fun fallbackSkipsEnginesAlreadyTried() {
        assertEquals(Recovery.Fallback(SYSTEM), plan(5, current = GOOGLE, tried = setOf(ON_DEVICE, GOOGLE)))
    }

    @Test fun noFallbackWhenEveryEngineHasBeenTried() {
        assertEquals(Recovery.None, plan(5, current = SYSTEM, tried = setOf(ON_DEVICE, GOOGLE, SYSTEM)))
    }

    @Test fun noFallbackWhenNoOtherEngineExists() {
        assertEquals(Recovery.None, plan(5, available = listOf(ON_DEVICE)))
    }

    @Test fun engineFailuresOfEveryKindFallBack() {
        for (code in listOf(1, 2, 4, 5, 10, 11, 99)) {
            assertEquals("code $code", Recovery.Fallback(GOOGLE), plan(code))
        }
        for (code in listOf(12, 13)) {
            assertEquals("code $code", Recovery.Fallback(SYSTEM), plan(code))
        }
    }

    @Test fun busyRecreatesTheSameEngine() {
        assertEquals(Recovery.RecreateSame(ON_DEVICE), plan(8, busyRetries = 0))
        assertEquals(Recovery.RecreateSame(ON_DEVICE), plan(8, busyRetries = 1))
    }

    @Test fun busyStopsAfterTheRetryLimit() {
        assertEquals(Recovery.None, plan(8, busyRetries = RecoveryPlanner.MAX_BUSY_RETRIES))
    }

    @Test fun noRecoveryOnceSomethingHasBeenHeard() {
        assertEquals(Recovery.None, plan(13, transcriptEmpty = false))
        assertEquals(Recovery.None, plan(8, transcriptEmpty = false))
    }

    @Test fun permissionAndMicProblemsDoNotFallBack() {
        assertEquals(Recovery.None, plan(9))
        assertEquals(Recovery.None, plan(3))
    }

    @Test fun silenceDoesNotFallBack() {
        assertEquals(Recovery.None, plan(7))
        assertEquals(Recovery.None, plan(6))
    }

    @Test fun engineChoiceFollowsAvailabilityOrder() {
        assertEquals(listOf(ON_DEVICE, GOOGLE, SYSTEM), SpeechEngine.available(SpeechEngine.Detection(true, "a/b", true)))
        assertEquals(listOf(GOOGLE), SpeechEngine.available(SpeechEngine.Detection(false, "a/b", false)))
        assertEquals(emptyList<SpeechEngine.Choice>(), SpeechEngine.available(SpeechEngine.Detection(false, null, false)))
    }

    @Test fun languageErrorOnDeviceGoesToTheStandardRecognizerFirst() {
        assertEquals(Recovery.Fallback(SYSTEM), plan(13, current = ON_DEVICE, available = all))
    }

    @Test fun languageErrorUsesGoogleWhenThereIsNoStandardRecognizer() {
        assertEquals(Recovery.Fallback(GOOGLE), plan(13, current = ON_DEVICE, available = listOf(ON_DEVICE, GOOGLE)))
    }

    @Test fun languageErrorWithNoOtherEngineFails() {
        assertEquals(Recovery.None, plan(13, current = ON_DEVICE, available = listOf(ON_DEVICE)))
    }

    @Test fun languageErrorFromStandardRecognizerTriesTheNextOne() {
        assertEquals(Recovery.Fallback(GOOGLE), plan(13, current = SYSTEM, tried = setOf(ON_DEVICE, SYSTEM)))
    }
}
