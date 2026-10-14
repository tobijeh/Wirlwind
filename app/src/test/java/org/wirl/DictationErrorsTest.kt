package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Test

class DictationErrorsTest {
    @Test fun everyRecognizerCodeHasASpecificMessage() {
        val expected = mapOf(
            1 to DictationError.NETWORK_TIMEOUT,
            2 to DictationError.NETWORK,
            3 to DictationError.MIC_BLOCKED,
            4 to DictationError.SERVER,
            5 to DictationError.CLIENT,
            6 to DictationError.NO_SPEECH,
            7 to DictationError.NO_SPEECH,
            8 to DictationError.BUSY,
            9 to DictationError.NO_PERMISSION,
            10 to DictationError.TOO_MANY_REQUESTS,
            11 to DictationError.SERVER,
            12 to DictationError.LANGUAGE,
            13 to DictationError.LANGUAGE,
        )
        for ((code, error) in expected) assertEquals("code $code", error, DictationError.from(code))
    }

    @Test fun audioErrorIsNotMistakenForNetwork() {
        assertEquals("Mic blocked by Android. Another app may be using it, or Wirl can't use it in the background.",
            DictationError.messageFor(DictationError.from(3), 3))
    }

    @Test fun unknownCodeShowsTheNumber() {
        assertEquals(DictationError.OTHER, DictationError.from(99))
        assertEquals("Speech error code 99.", DictationError.messageFor(DictationError.OTHER, 99))
    }

    @Test fun unknownWithoutCodeShowsGenericMessage() {
        assertEquals("Dictation stopped unexpectedly.", DictationError.messageFor(DictationError.OTHER, null))
    }

    @Test fun knownErrorsKeepTheirOwnMessage() {
        assertEquals(DictationError.BUSY.message, DictationError.messageFor(DictationError.BUSY, 8))
    }

    @Test fun securityExceptionMeansMicBlocked() {
        assertEquals(DictationError.MIC_BLOCKED, DictationError.from(SecurityException("microphone")))
    }

    @Test fun otherExceptionsAreUnexpected() {
        assertEquals(DictationError.OTHER, DictationError.from(IllegalStateException("not ready")))
    }

    @Test fun messageNamesEachCauseSpecifically() {
        assertEquals("No mic permission. Allow the microphone for Wirl in Settings.", DictationError.NO_PERMISSION.message)
        assertEquals("Network error. Check your internet connection.", DictationError.NETWORK.message)
        assertEquals("Speech recognizer is busy. Try again in a moment.", DictationError.BUSY.message)
    }
}
