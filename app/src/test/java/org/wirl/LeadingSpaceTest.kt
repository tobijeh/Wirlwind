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
    private data class Run(val piece: String, val allowed: Boolean, val after: String, val guardedAfter: String, val cursorAfter: Int)

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
        val allowed = FieldState.mayAddLeadingSpace(reported, hint, contentDescription, showingHint, selectionStart, selectionEnd)
        val existing = FieldText.visible(reported, hint, showingHint, selectionStart, selectionEnd, contentDescription)
        val piece = TextInsertion.pieceFor(existing, selectionStart, selectionEnd, transcript, allowLeadingSpace = allowed)
        DictationPaste.insert(Clip().also { it.set(piece) }, object : FieldPort {
            override fun paste(): Boolean {
                field.pasteAtCursor(piece)
                return true
            }
            override fun setText(text: String, cursor: Int) = field.setText(text, cursor)
        }, piece) { null }
        val after = field.buffer
        val corrected = LeadingWhitespaceGuard.correct(allowed, after)
        val guarded = corrected ?: after
        val cursor = if (corrected != null) guarded.length else field.cursor
        return Run(piece, allowed, after, guarded, cursor)
    }

    /** The WhatsApp empty message box, exactly as the accessibility tree reported it in the field. */
    private fun whatsAppEmptyBox() = Field(buffer = "", cursor = 0)

    @Test fun whatsAppEmptyBoxInsertsWithNoLeadingSpaceAtAnyStage() {
        // getText "Message", hintText null, isShowingHintText false, contentDescription null, selection -1..-1.
        val field = whatsAppEmptyBox()
        val run = dictate(field, "Message", hint = null, contentDescription = null, showingHint = false,
            selectionStart = -1, selectionEnd = -1, transcript = "Hi there")

        assertFalse(run.allowed)
        assertEquals("Hi there", run.piece)
        assertEquals("Hi there", run.after)
        assertEquals("Hi there", run.guardedAfter)
    }

    @Test fun whatsAppEmptyBoxWithContentDescriptionMessageAlsoGetsNoLeadingSpace() {
        val field = whatsAppEmptyBox()
        val run = dictate(field, "Message", hint = null, contentDescription = "Message", showingHint = false,
            selectionStart = -1, selectionEnd = -1, transcript = "Hi there")
        assertFalse(run.piece.startsWith(" "))
        assertEquals("Hi there", run.guardedAfter)
    }

    @Test fun validFieldHelloWithCursorAt5GetsHelloWorld() {
        val field = Field(buffer = "Hello", cursor = 5)
        val run = dictate(field, "Hello", hint = null, contentDescription = null, showingHint = false,
            selectionStart = 5, selectionEnd = 5, transcript = "world")

        assertTrue(run.allowed)
        assertEquals(" world", run.piece)
        assertEquals("Hello world", run.after)
        assertEquals("Hello world", run.guardedAfter)
        assertFalse(run.after.contains("  "))
    }

    @Test fun noValidCursorIsNeverReadAsTypedText() {
        // A -1 cursor means the text can't be trusted: it's treated as nothing, so cleanup never sees it.
        assertEquals("", FieldText.visible("Hello there", null, false, -1, -1))
        assertEquals("", FieldText.visible("Message", null, false, -1, -1, "Message"))
        assertFalse(FieldState.mayAddLeadingSpace("Hello there", null, null, false, -1, -1))
        assertFalse(FieldState.mayAddLeadingSpace("Hello there", null, null, false, 11, -1))
    }

    @Test fun typedMessageWithCursorAtEndIsKept() {
        // A real word that happens to match a placeholder is still text when the cursor is after it.
        assertEquals("Message", FieldText.visible("Message", null, false, 7, 7, "Message"))
        assertTrue(FieldState.mayAddLeadingSpace("Message", null, null, false, 7, 7))
    }

    @Test fun showingHintNeverGetsALeadingSpace() {
        assertFalse(FieldState.mayAddLeadingSpace("Type a message", "Type a message", null, true, 14, 14))
    }

    @Test fun cursorAtZeroCountsAsEmptyAndGetsNoLeadingSpace() {
        // The field holds "Hello" but the cursor is at the start, so nothing is before it: no leading space.
        val field = Field(buffer = "Hello", cursor = 0)
        val run = dictate(field, "Hello", hint = null, contentDescription = null, showingHint = false,
            selectionStart = 0, selectionEnd = 0, transcript = "Hi")
        assertFalse(run.allowed)
        assertFalse(run.piece.startsWith(" "))
        assertFalse(run.after.startsWith(" "))
        assertEquals("Hi Hello", run.after)
    }

    @Test fun hiddenWhitespaceInAnEmptyFieldIsRemovedAfterInsert() {
        // The real buffer holds a space the user can't see. Blank text reads as empty, so the paste lands after it.
        val field = Field(buffer = " ", cursor = 1)
        val run = dictate(field, " ", hint = null, contentDescription = null, showingHint = false,
            selectionStart = 1, selectionEnd = 1, transcript = "Hi there")

        assertFalse(run.allowed)
        assertEquals(" Hi there", run.after)
        assertEquals("Hi there", run.guardedAfter)
        assertEquals("Hi there".length, run.cursorAfter)
    }

    @Test fun trailingSpaceAlreadyPresentStillGivesOneSpace() {
        val field = Field(buffer = "Hello ", cursor = 6)
        val run = dictate(field, "Hello ", hint = null, contentDescription = null, showingHint = false,
            selectionStart = 6, selectionEnd = 6, transcript = "world")
        assertEquals("Hello world", run.after)
        assertFalse(run.after.contains("  "))
    }

    @Test fun guardOnlyRemovesLeadingWhitespaceWhenNoLeadingSpaceWasAllowed() {
        assertEquals("Hi there", LeadingWhitespaceGuard.correct(leadingSpaceAllowed = false, current = "  Hi there"))
        assertEquals("Hi  there", LeadingWhitespaceGuard.correct(leadingSpaceAllowed = false, current = "\tHi  there"))
        assertNull("a space was allowed, so the text is left alone", LeadingWhitespaceGuard.correct(leadingSpaceAllowed = true, current = " Hello"))
        assertNull(LeadingWhitespaceGuard.correct(leadingSpaceAllowed = false, current = "Hi"))
        assertNull(LeadingWhitespaceGuard.correct(leadingSpaceAllowed = false, current = ""))
    }

    @Test fun markersShowWhitespace() {
        assertEquals("[·Hi·there]", Visible.mark(" Hi there"))
        assertEquals("[a⏎b→c]", Visible.mark("a\nb\tc"))
        assertEquals("null", Visible.mark(null))
    }
}
