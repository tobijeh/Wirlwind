package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Test

class DictationPasteTest {
    /** A text field whose accessibility text can differ from what's really in it, as with a placeholder. */
    private class FakeField(var buffer: String, var cursor: Int, val reportedText: String, val pasteWorks: Boolean = true) : FieldPort {
        override fun paste(): Boolean = pasteWorks

        /** Pasting goes into the real buffer at the cursor. The reported text is not touched. */
        fun pasteInto(piece: String) {
            buffer = buffer.substring(0, cursor) + piece + buffer.substring(cursor)
            cursor += piece.length
        }

        override fun setText(text: String, cursor: Int): Boolean {
            buffer = text
            this.cursor = cursor
            return true
        }
    }

    private class FakeClipboard(var contents: String?) : ClipboardPort<String> {
        override fun current(): String? = contents
        override fun set(text: String) {
            contents = text
        }
        override fun restore(saved: String?) {
            contents = saved
        }
    }

    /** Runs the paste like the app does: paste puts the clipboard into the field. */
    private fun dictate(
        field: FakeField,
        clipboard: FakeClipboard,
        existing: String,
        selectionStart: Int,
        selectionEnd: Int,
        transcript: String,
    ): InsertMethod {
        val piece = TextInsertion.pieceFor(existing, selectionStart, selectionEnd, transcript)
        return DictationPaste.insert(
            clipboard,
            object : FieldPort {
                override fun paste(): Boolean {
                    if (!field.pasteWorks) return false
                    field.pasteInto(clipboard.contents ?: "")
                    return true
                }

                override fun setText(text: String, cursor: Int) = field.setText(text, cursor)
            },
            piece,
        ) {
            val plan = TextInsertion.plan(existing, selectionStart, selectionEnd, transcript)
            plan.text to plan.cursor
        }
    }

    @Test fun dictationIntoAnEmptyWhatsAppStyleFieldInsertsOnlyTheDictatedText() {
        // The field reports "Message" as its text (a placeholder), but its real content is empty.
        val field = FakeField(buffer = "", cursor = 0, reportedText = "Message")
        val clipboard = FakeClipboard("my clipboard")
        // The app sees the placeholder as nothing typed, so the existing text is empty.
        val visible = FieldText.visible("Message", hint = null, showingHint = false, selectionStart = 0, selectionEnd = 0, contentDescription = "Message")

        val method = dictate(field, clipboard, existing = visible, selectionStart = 0, selectionEnd = 0, transcript = "Hi there")

        assertEquals(InsertMethod.PASTE, method)
        assertEquals("Hi there", field.buffer)
    }

    @Test fun clipboardIsRestoredAfterPaste() {
        val field = FakeField(buffer = "", cursor = 0, reportedText = "")
        val clipboard = FakeClipboard("my clipboard")

        dictate(field, clipboard, existing = "", selectionStart = 0, selectionEnd = 0, transcript = "Hi there")

        assertEquals("my clipboard", clipboard.contents)
    }

    @Test fun emptyClipboardIsRestoredAsEmpty() {
        val field = FakeField(buffer = "", cursor = 0, reportedText = "")
        val clipboard = FakeClipboard(null)

        dictate(field, clipboard, existing = "", selectionStart = 0, selectionEnd = 0, transcript = "Hi")

        assertEquals(null, clipboard.contents)
    }

    @Test fun pasteAtTheEndOfTypedTextAddsASpace() {
        val field = FakeField(buffer = "Hello", cursor = 5, reportedText = "Hello")
        val clipboard = FakeClipboard("keep me")

        dictate(field, clipboard, existing = "Hello", selectionStart = 5, selectionEnd = 5, transcript = "there")

        assertEquals("Hello there", field.buffer)
        assertEquals("keep me", clipboard.contents)
    }

    @Test fun typedMessageIsKeptWhenDictatingAfterIt() {
        val field = FakeField(buffer = "Message", cursor = 7, reportedText = "Message")
        val clipboard = FakeClipboard("keep me")
        val visible = FieldText.visible("Message", hint = null, showingHint = false, selectionStart = 7, selectionEnd = 7, contentDescription = "Message")

        dictate(field, clipboard, existing = visible, selectionStart = 7, selectionEnd = 7, transcript = "there")

        assertEquals("Message there", field.buffer)
    }

    @Test fun clipboardIsRestoredEvenWhenPasteFails() {
        val field = FakeField(buffer = "", cursor = 0, reportedText = "", pasteWorks = false)
        val clipboard = FakeClipboard("keep me")

        val method = dictate(field, clipboard, existing = "", selectionStart = 0, selectionEnd = 0, transcript = "Hi")

        assertEquals("keep me", clipboard.contents)
        assertEquals(InsertMethod.SET_TEXT, method)
        assertEquals("Hi", field.buffer)
    }

    @Test fun setTextIsOnlyUsedWhenPasteFails() {
        val field = FakeField(buffer = "", cursor = 0, reportedText = "")
        val clipboard = FakeClipboard("keep me")
        var fallbackUsed = false

        val method = DictationPaste.insert(clipboard, object : FieldPort {
            override fun paste(): Boolean = true
            override fun setText(text: String, cursor: Int): Boolean {
                fallbackUsed = true
                return true
            }
        }, "Hi") {
            fallbackUsed = true
            "Hi" to 2
        }

        assertEquals(InsertMethod.PASTE, method)
        assertEquals(false, fallbackUsed)
    }

    @Test fun pieceAddsSpacesOnlyWhereWordsWouldRunTogether() {
        assertEquals("Hi", TextInsertion.pieceFor("", 0, 0, " Hi "))
        assertEquals(" there", TextInsertion.pieceFor("Hello", 5, 5, "there"))
        assertEquals("there ", TextInsertion.pieceFor("world", 0, 0, "there"))
        assertEquals("there", TextInsertion.pieceFor("Hello ", 6, 6, "there"))
        assertEquals("small", TextInsertion.pieceFor("Hello big world", 6, 9, "small").trim())
    }
}
