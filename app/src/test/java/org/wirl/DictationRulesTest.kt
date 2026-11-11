package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertEquals(DictationError.NETWORK, DictationError.from(2))
        assertEquals(DictationError.BUSY, DictationError.from(8))
        assertEquals(DictationError.OTHER, DictationError.from(99))
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

    @Test fun aFieldWithNoViewIdIsStillTheSameField() {
        val start = FieldKey("com.anthropic.claude", "", 0, 0)
        assertTrue("no id at the start and no id now", DictationPlacement.sameField(start, "com.anthropic.claude", null))
        assertTrue(DictationPlacement.sameField(FieldKey("a", "a:id/box", 0, 0), "a", "a:id/box"))
        assertFalse(DictationPlacement.sameField(FieldKey("a", "a:id/box", 0, 0), "a", "a:id/other"))
        assertFalse(DictationPlacement.sameField(start, "com.other", null))
    }

    @Test fun theClipboardReasonSaysWhatChanged() {
        val start = FieldKey("a", "", 0, 0)
        assertEquals("no editable field has focus", DictationPlacement.clipboardReason(start, false, null, null))
        assertEquals("a different app (a → b)", DictationPlacement.clipboardReason(start, true, "b", null))
        assertEquals("a different field (no id → a:id/x)", DictationPlacement.clipboardReason(start, true, "a", "a:id/x"))
    }
}
