package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Test
import org.wirl.SpeechEngine.Choice.GOOGLE_APP
import org.wirl.SpeechEngine.Choice.GOOGLE_TTS
import org.wirl.SpeechEngine.Choice.ON_DEVICE
import org.wirl.SpeechEngine.Choice.SYSTEM

class RecoveryPlannerTest {
    private val withOnDevice = listOf(ON_DEVICE, GOOGLE_APP, GOOGLE_TTS, SYSTEM)
    private val online = listOf(GOOGLE_APP, GOOGLE_TTS, SYSTEM)

    private fun plan(
        code: Int,
        current: SpeechEngine.Choice = GOOGLE_APP,
        tried: Set<SpeechEngine.Choice> = setOf(current),
        available: List<SpeechEngine.Choice> = online,
        transcriptEmpty: Boolean = true,
        busyRetries: Int = 0,
    ) = RecoveryPlanner.plan(code, transcriptEmpty, current, tried, available, busyRetries)

    @Test fun languageErrorOnDeviceRetriesImmediatelyWithTheNextEngine() {
        assertEquals(Recovery.Fallback(GOOGLE_APP), plan(13, current = ON_DEVICE, tried = setOf(ON_DEVICE), available = withOnDevice))
    }

    @Test fun languageErrorFromAnOnlineEngineMovesOnInOrder() {
        assertEquals(Recovery.Fallback(GOOGLE_TTS), plan(13, current = GOOGLE_APP))
        assertEquals(Recovery.Fallback(SYSTEM), plan(12, current = GOOGLE_TTS, tried = setOf(GOOGLE_APP, GOOGLE_TTS)))
    }

    @Test fun fallbackSkipsEnginesAlreadyTried() {
        assertEquals(Recovery.Fallback(SYSTEM), plan(5, current = GOOGLE_TTS, tried = setOf(GOOGLE_APP, GOOGLE_TTS)))
    }

    @Test fun noFallbackWhenEveryEngineHasBeenTried() {
        assertEquals(Recovery.None, plan(13, current = SYSTEM, tried = setOf(GOOGLE_APP, GOOGLE_TTS, SYSTEM)))
    }

    @Test fun engineFailuresOfEveryKindMoveOn() {
        for (code in listOf(1, 2, 4, 5, 10, 11, 12, 13, 99)) {
            assertEquals("code $code", Recovery.Fallback(GOOGLE_TTS), plan(code, current = GOOGLE_APP))
        }
    }

    @Test fun busyRecreatesTheSameEngine() {
        assertEquals(Recovery.RecreateSame(GOOGLE_APP), plan(8, busyRetries = 0))
        assertEquals(Recovery.RecreateSame(GOOGLE_APP), plan(8, busyRetries = 1))
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

    @Test fun aSimulatedRunTriesEveryEngineOnceThenStops() {
        // Every engine answers with a language error: each is tried once, in order, then dictation gives up.
        val tried = mutableSetOf(GOOGLE_APP)
        val order = mutableListOf(GOOGLE_APP)
        var current = GOOGLE_APP
        while (true) {
            val step = plan(13, current = current, tried = tried.toSet(), available = online)
            if (step !is Recovery.Fallback) break
            current = step.engine
            tried += current
            order += current
        }
        assertEquals(listOf(GOOGLE_APP, GOOGLE_TTS, SYSTEM), order)
    }
}
