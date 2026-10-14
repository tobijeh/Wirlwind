package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Test

class DictationRulesTest {
    @Test fun aiOffAlwaysInsertsTheRawTranscript() {
        assertEquals("um hello", DictationResult.choose("um hello", aiEnabled = false, cleaned = "Hello."))
        assertEquals("um hello", DictationResult.choose("um hello", aiEnabled = false, cleaned = null))
    }

    @Test fun aiOnInsertsTheCleanedText() {
        assertEquals("Hello.", DictationResult.choose("um hello", aiEnabled = true, cleaned = "Hello."))
    }

    @Test fun cleanupFailureInsertsTheRawTranscript() {
        assertEquals("um hello", DictationResult.choose("um hello", aiEnabled = true, cleaned = null))
    }

    @Test fun blankCleanupInsertsTheRawTranscript() {
        assertEquals("um hello", DictationResult.choose("um hello", aiEnabled = true, cleaned = "  "))
    }

    @Test fun errorCodesMapToMessages() {
        assertEquals(DictationError.NO_PERMISSION, DictationError.from(9))
        assertEquals(DictationError.NO_SPEECH, DictationError.from(7))
        assertEquals(DictationError.NO_SPEECH, DictationError.from(6))
        assertEquals(DictationError.NO_INTERNET, DictationError.from(2))
        assertEquals(DictationError.BUSY, DictationError.from(8))
        assertEquals(DictationError.OTHER, DictationError.from(99))
    }

    @Test fun messagesAreShortAndClear() {
        assertEquals("Microphone permission needed. Open Wirl settings to allow it.", DictationError.NO_PERMISSION.message)
        assertEquals("Speech recognition isn't available on this phone.", DictationError.RECOGNIZER_UNAVAILABLE.message)
        assertEquals("No speech heard.", DictationError.NO_SPEECH.message)
    }

    @Test fun dictationGoesIntoTheSameEditableField() {
        assertEquals(DictationPlacement.IN_FIELD, DictationPlacement.decide(fieldFound = true, sameField = true, editable = true))
    }

    @Test fun nonEditableFieldCopiesToClipboard() {
        assertEquals(DictationPlacement.CLIPBOARD, DictationPlacement.decide(fieldFound = true, sameField = true, editable = false))
    }

    @Test fun missingFieldCopiesToClipboard() {
        assertEquals(DictationPlacement.CLIPBOARD, DictationPlacement.decide(fieldFound = false, sameField = false, editable = false))
    }

    @Test fun differentFieldCopiesToClipboard() {
        assertEquals(DictationPlacement.CLIPBOARD, DictationPlacement.decide(fieldFound = true, sameField = false, editable = true))
    }
}
