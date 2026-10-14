package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Test

class FieldTextTest {
    @Test fun platformSaysHintIsShowingMeansEmpty() {
        assertEquals("", FieldText.visible("Message", "Message", showingHint = true, selectionStart = 0, selectionEnd = 0))
    }

    @Test fun hintEqualToTextWithCursorAtStartMeansEmpty() {
        // WhatsApp-style: the hint is the field's text, but the platform doesn't flag it.
        assertEquals("", FieldText.visible("Message", "Message", showingHint = false, selectionStart = 0, selectionEnd = 0))
    }

    @Test fun hintWithUnknownCursorMeansEmpty() {
        assertEquals("", FieldText.visible("Message", "Message", showingHint = false, selectionStart = -1, selectionEnd = -1))
    }

    @Test fun blankAndWhitespaceMeanEmpty() {
        assertEquals("", FieldText.visible("   ", "Message", showingHint = false, selectionStart = 3, selectionEnd = 3))
        assertEquals("", FieldText.visible("\n\t", null, showingHint = false, selectionStart = 2, selectionEnd = 2))
    }

    @Test fun nullTextMeansEmpty() {
        assertEquals("", FieldText.visible(null, "Message", showingHint = false, selectionStart = 0, selectionEnd = 0))
    }

    @Test fun userTypedMessageIsKeptWhenCursorIsAfterIt() {
        assertEquals("Message", FieldText.visible("Message", "Message", showingHint = false, selectionStart = 7, selectionEnd = 7))
    }

    @Test fun ordinaryTextIsKept() {
        assertEquals("Hello there", FieldText.visible("Hello there", "Message", showingHint = false, selectionStart = 11, selectionEnd = 11))
    }

    @Test fun textWithNoHintIsKept() {
        assertEquals("Message", FieldText.visible("Message", null, showingHint = false, selectionStart = 0, selectionEnd = 0))
    }

    @Test fun dictationIntoAnEmptyFieldWithHintInsertsOnlyTheDictatedText() {
        val existing = FieldText.visible("Message", "Message", showingHint = false, selectionStart = 0, selectionEnd = 0)
        val plan = TextInsertion.plan(existing, 0, 0, "Hi there")
        assertEquals("Hi there", plan.text)
    }

    @Test fun dictationAfterUserTypedMessageKeepsIt() {
        val existing = FieldText.visible("Message", "Message", showingHint = false, selectionStart = 7, selectionEnd = 7)
        val plan = TextInsertion.plan(existing, 7, 7, "there")
        assertEquals("Message there", plan.text)
    }
}
