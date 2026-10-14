package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Test

class TextInsertionTest {
    @Test fun emptyFieldGetsTheTextWithCursorAtTheEnd() {
        assertEquals(Insertion("Hello there.", 12), TextInsertion.plan("", -1, -1, "Hello there."))
    }

    @Test fun insertedTextIsTrimmed() {
        assertEquals(Insertion("Hi.", 3), TextInsertion.plan("", 0, 0, "  Hi.  \n"))
    }

    @Test fun cursorAtStartInsertsBeforeExistingText() {
        // No space goes before (nothing there), but one goes after so the words don't run together.
        assertEquals(Insertion("Hi world", 2), TextInsertion.plan("world", 0, 0, "Hi"))
    }

    @Test fun cursorAtStartOfTextWithSpaceAlreadyPresent() {
        assertEquals(Insertion("Hi world", 2), TextInsertion.plan(" world", 0, 0, "Hi"))
    }

    @Test fun cursorInTheMiddleOfAWord() {
        assertEquals(Insertion("Hello there world", 11), TextInsertion.plan("Helloworld", 5, 5, "there"))
    }

    @Test fun cursorInTheMiddleAfterASpace() {
        assertEquals(Insertion("Hello big new world", 13), TextInsertion.plan("Hello  world", 6, 6, "big new"))
    }

    @Test fun cursorAtTheEndAddsASpaceBefore() {
        assertEquals(Insertion("Hello there", 11), TextInsertion.plan("Hello", 5, 5, "there"))
    }

    @Test fun cursorAfterASpaceAddsNoExtraSpace() {
        assertEquals(Insertion("Hello there", 11), TextInsertion.plan("Hello ", 6, 6, "there"))
    }

    @Test fun selectionIsReplaced() {
        assertEquals(Insertion("Hello small world", 11), TextInsertion.plan("Hello big world", 6, 9, "small"))
    }

    @Test fun reversedSelectionIsHandled() {
        // A selection given end-first shouldn't drop text.
        assertEquals(Insertion("Hello small world", 11), TextInsertion.plan("Hello big world", 9, 6, "small"))
    }

    @Test fun unknownCursorInsertsAtTheEnd() {
        assertEquals(Insertion("Hello there", 11), TextInsertion.plan("Hello", -1, -1, "there"))
    }

    @Test fun emptyDictationLeavesTheFieldAlone() {
        assertEquals(Insertion("Hello", 5), TextInsertion.plan("Hello", 5, 5, "   "))
    }

    @Test fun newlineBeforeCursorNeedsNoSpace() {
        assertEquals(Insertion("Line one\nLine two", 17), TextInsertion.plan("Line one\n", 9, 9, "Line two"))
    }
}
