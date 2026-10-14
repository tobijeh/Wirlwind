package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeadingSpaceTest {
    /** A field whose real buffer and cursor are known, as the paste acts on them. */
    private class Field(var buffer: String, var cursor: Int) : FieldPort {
        override fun paste(): Boolean = true
        override fun setText(text: String, cursor: Int): Boolean {
            buffer = text
            this.cursor = cursor
            return true
        }
        fun pasteAtCursor(piece: String) {
            buffer = buffer.substring(0, cursor) + piece + buffer.substring(cursor)
            cursor += piece.length
        }
    }

    private class Clip : ClipboardPort<String> {
        var contents: String? = null
        override fun current(): String? = contents
        override fun set(text: String) {
            contents = text
        }
        override fun restore(saved: String?) {
            contents = saved
        }
    }

    /**
     * Runs a dictation the way the app does, returning the piece at each stage and the field afterwards. [reported] is
     * what the accessibility tree says the field holds; [field] is what's really in it.
     */
    private data class Run(val piece: String, val wasEmpty: Boolean, val after: String, val guardedAfter: String, val cursorAfter: Int)

    private fun dictate(
        field: Field,
        reported: String?,
        hint: String?,
        contentDescription: String?,
        showingHint: Boolean,
        selectionStart: Int,
        selectionEnd: Int,
        transcript: String,
    ): Run {
        val wasEmpty = FieldState.wasEmpty(reported, hint, contentDescription, showingHint, selectionStart, selectionEnd)
        val existing = FieldText.visible(reported, hint, showingHint, selectionStart, selectionEnd, contentDescription)
        val piece = TextInsertion.pieceFor(existing, selectionStart, selectionEnd, transcript, allowLeadingSpace = !wasEmpty)
        DictationPaste.insert(Clip().also { it.set(piece) }, object : FieldPort {
            override fun paste(): Boolean {
                field.pasteAtCursor(piece)
                return true
            }
            override fun setText(text: String, cursor: Int) = field.setText(text, cursor)
        }, piece) { null }
        val after = field.buffer
        val corrected = LeadingWhitespaceGuard.correct(wasEmpty, after)
        val guarded = corrected ?: after
        val cursor = if (corrected != null) guarded.length else field.cursor
        return Run(piece, wasEmpty, after, guarded, cursor)
    }

    @Test fun emptyWhatsAppFieldHasNoLeadingSpaceAtAnyStage() {
        // The placeholder "Message" is reported, but the real buffer is empty and the cursor is at the start.
        val field = Field(buffer = "", cursor = 0)
        val run = dictate(field, "Message", hint = null, contentDescription = "Message", showingHint = false,
            selectionStart = 0, selectionEnd = 0, transcript = "Hi there")

        assertTrue(run.wasEmpty)
        assertFalse("piece has a leading space", run.piece.startsWith(" "))
        assertEquals("Hi there", run.after)
        assertEquals("Hi there", run.guardedAfter)
    }

    @Test fun cursorAtZeroCountsAsEmptyAndGetsNoLeadingSpace() {
        // The field holds "Hello" but the cursor is at the start, so nothing is before it: no leading space.
        val field = Field(buffer = "Hello", cursor = 0)
        val run = dictate(field, "Hello", hint = null, contentDescription = null, showingHint = false,
            selectionStart = 0, selectionEnd = 0, transcript = "Hi")
        assertTrue(run.wasEmpty)
        assertFalse(run.piece.startsWith(" "))
        assertFalse(run.after.startsWith(" "))
        assertEquals("Hi Hello", run.after)
    }

    @Test fun hiddenWhitespaceInAnEmptyFieldIsRemovedAfterInsert() {
        // The real buffer holds a space the user can't see. Blank text reads as empty, so the paste lands after it.
        val field = Field(buffer = " ", cursor = 1)
        val run = dictate(field, " ", hint = null, contentDescription = null, showingHint = false,
            selectionStart = 1, selectionEnd = 1, transcript = "Hi there")

        assertTrue(run.wasEmpty)
        assertEquals(" Hi there", run.after)
        assertEquals("Hi there", run.guardedAfter)
        assertEquals("Hi there".length, run.cursorAfter)
    }

    @Test fun fieldWithHelloGetsHelloWorldWithExactlyOneSpace() {
        val field = Field(buffer = "Hello", cursor = 5)
        val run = dictate(field, "Hello", hint = null, contentDescription = null, showingHint = false,
            selectionStart = 5, selectionEnd = 5, transcript = "world")

        assertFalse(run.wasEmpty)
        assertEquals(" world", run.piece)
        assertEquals("Hello world", run.after)
        assertEquals("Hello world", run.guardedAfter)
    }

    @Test fun trailingSpaceAlreadyPresentStillGivesOneSpace() {
        val field = Field(buffer = "Hello ", cursor = 6)
        val run = dictate(field, "Hello ", hint = null, contentDescription = null, showingHint = false,
            selectionStart = 6, selectionEnd = 6, transcript = "world")
        assertEquals("Hello world", run.after)
        assertFalse(run.after.contains("  "))
    }

    @Test fun guardOnlyRemovesLeadingWhitespaceAndOnlyForEmptyFields() {
        assertEquals("Hi there", LeadingWhitespaceGuard.correct(wasEmpty = true, current = "  Hi there"))
        assertEquals("Hi  there", LeadingWhitespaceGuard.correct(wasEmpty = true, current = "\tHi  there"))
        assertNull("a field the user had text in is left alone", LeadingWhitespaceGuard.correct(wasEmpty = false, current = " Hello"))
        assertNull(LeadingWhitespaceGuard.correct(wasEmpty = true, current = "Hi"))
        assertNull(LeadingWhitespaceGuard.correct(wasEmpty = true, current = ""))
    }

    @Test fun markersShowWhitespace() {
        assertEquals("[·Hi·there]", Visible.mark(" Hi there"))
        assertEquals("[a⏎b→c]", Visible.mark("a\nb\tc"))
        assertEquals("null", Visible.mark(null))
    }

    @Test fun placeholderOrHintOrDescriptionMeansEmpty() {
        assertTrue(FieldState.wasEmpty("Message", hint = "Message", contentDescription = null, showingHint = false, selectionStart = 7, selectionEnd = 7))
        assertTrue(FieldState.wasEmpty("Message", hint = null, contentDescription = "Message", showingHint = false, selectionStart = 7, selectionEnd = 7))
        assertTrue(FieldState.wasEmpty("Anything", hint = null, contentDescription = null, showingHint = true, selectionStart = 3, selectionEnd = 3))
        assertTrue(FieldState.wasEmpty("   ", hint = null, contentDescription = null, showingHint = false, selectionStart = 3, selectionEnd = 3))
        assertFalse(FieldState.wasEmpty("Hello", hint = null, contentDescription = null, showingHint = false, selectionStart = 5, selectionEnd = 5))
    }
}
