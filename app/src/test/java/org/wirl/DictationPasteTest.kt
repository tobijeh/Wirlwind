package org.wirl

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

        override fun readText(): String = buffer
    }

    private class FakeClipboard(var contents: String?) : ClipboardPort<String> {
        override fun current(): String? = contents
        override fun set(text: String) {
            contents = text
        }
        var restore: ClipboardRestore? = null

        /** Runs at once rather than after a delay. */
        override fun restoreIfStillOurs(saved: String?, ours: String, done: (ClipboardRestore) -> Unit) {
            val decision = ClipboardRestore.decide(contents, ours)
            if (ClipboardRestore.putsBack(decision)) contents = saved
            restore = decision
            done(decision)
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
    ): InsertMethod = runBlocking {
        val piece = TextInsertion.pieceFor(existing, selectionStart, selectionEnd, transcript)
        DictationPaste.insert(
            clipboard,
            object : FieldPort {
                override fun paste(): Boolean {
                    if (!field.pasteWorks) return false
                    field.pasteInto(clipboard.contents ?: "")
                    return true
                }

                override fun setText(text: String, cursor: Int) = field.setText(text, cursor)

                override fun readText(): String = field.buffer
            },
            piece,
            sleep = {},
        ) {
            val plan = TextInsertion.plan(existing, selectionStart, selectionEnd, transcript)
            plan.text to plan.cursor
        }.method
    }

    /**
     * A field for the paste check: [landsOn] says which paste attempts (1, 2, …) actually put text in. [readable] false
     * means its text can't be read. [setTextWorks] false means set-text fails too.
     */
    private class CheckedField(
        var text: String,
        val landsOn: Set<Int>,
        val readable: Boolean = true,
        val setTextWorks: Boolean = true,
        val clipboard: FakeClipboard,
    ) : FieldPort {
        var pastes = 0
        var setTexts = 0

        override fun paste(): Boolean {
            pastes++
            if (pastes in landsOn) text += clipboard.contents.orEmpty()
            return true
        }

        override fun setText(text: String, cursor: Int): Boolean {
            setTexts++
            if (setTextWorks) this.text = text
            return setTextWorks
        }

        override fun readText(): String? = if (readable) text else null
    }

    private fun check(field: CheckedField, clipboard: FakeClipboard, piece: String = "Hi"): PasteResult = runBlocking {
        var restore: ClipboardRestore? = null
        DictationPaste.insert(clipboard, field, piece, onRestore = { restore = it }, sleep = {}) { field.text + piece to 0 }
            .also { org.junit.Assert.assertNotNull("onRestore always says what happened to the clipboard", restore) }
    }

    @Test fun aPasteThatLandsIsSentOnce() {
        val clipboard = FakeClipboard("mine")
        val field = CheckedField("", landsOn = setOf(1), clipboard = clipboard)
        val result = check(field, clipboard)
        assertEquals(PasteResult(InsertMethod.PASTE, PasteCheck.LANDED), result)
        assertEquals(1, field.pastes)
        assertEquals("Hi", field.text)
        assertEquals(ClipboardRestore.RESTORED, clipboard.restore)
        assertEquals("mine", clipboard.contents)
    }

    @Test fun aMissedPasteIsSentOnceMore() {
        val clipboard = FakeClipboard("mine")
        val field = CheckedField("", landsOn = setOf(2), clipboard = clipboard)
        val result = check(field, clipboard)
        assertEquals(PasteResult(InsertMethod.PASTE, PasteCheck.LANDED_ON_RETRY), result)
        assertEquals(2, field.pastes)
        assertEquals("only once in the field", "Hi", field.text)
        assertEquals(0, field.setTexts)
    }

    @Test fun aPasteThatNeverLandsFallsBackToSetText() {
        val clipboard = FakeClipboard("mine")
        val field = CheckedField("Hello ", landsOn = emptySet(), clipboard = clipboard)
        val result = check(field, clipboard)
        assertEquals(PasteResult(InsertMethod.SET_TEXT, PasteCheck.MISSED_SET_TEXT), result)
        assertEquals("pasted twice, then set-text", 2, field.pastes)
        assertEquals("Hello Hi", field.text)
        assertEquals("it landed by set-text, so the clipboard is put back", "mine", clipboard.contents)
    }

    @Test fun whenSetTextFailsTooTheDictationStaysOnTheClipboard() {
        val clipboard = FakeClipboard("mine")
        val field = CheckedField("", landsOn = emptySet(), setTextWorks = false, clipboard = clipboard)
        var restore: ClipboardRestore? = null
        val result = runBlocking {
            DictationPaste.insert(clipboard, field, "Hi", onRestore = { restore = it }, sleep = {}) { "Hi" to 2 }
        }
        assertEquals(PasteResult(InsertMethod.FAILED, PasteCheck.MISSED_CLIPBOARD), result)
        assertEquals("not put back: the dictation stays for pasting by hand", "Hi", clipboard.contents)
        assertEquals(ClipboardRestore.KEPT_NOT_LANDED, restore)
        assertEquals(null, clipboard.restore)
    }

    @Test fun anUnreadableFieldIsNeverPastedTwice() {
        val clipboard = FakeClipboard("mine")
        val field = CheckedField("", landsOn = emptySet(), readable = false, clipboard = clipboard)
        val result = check(field, clipboard)
        assertEquals(PasteResult(InsertMethod.PASTE, PasteCheck.UNCHECKED), result)
        assertEquals(1, field.pastes)
        assertEquals(0, field.setTexts)
    }

    @Test fun theClipboardIsLeftAloneIfSomethingElseWasCopied() {
        val clipboard = FakeClipboard("mine")
        val field = object : FieldPort {
            var text = ""
            override fun paste(): Boolean {
                text += clipboard.contents
                // The user copies something else before the restore runs.
                clipboard.contents = "copied later"
                return true
            }
            override fun setText(text: String, cursor: Int) = true
            override fun readText(): String = text
        }
        runBlocking { DictationPaste.insert(clipboard, field, "Hi", sleep = {}) { null } }
        assertEquals("copied later", clipboard.contents)
        assertEquals(ClipboardRestore.SKIPPED_CHANGED, clipboard.restore)
    }

    @Test fun anUnreadableClipboardIsLeftAloneRatherThanEmptied() {
        assertEquals(ClipboardRestore.LEFT_UNREADABLE, ClipboardRestore.decide(null, "Hi"))
        assertEquals(false, ClipboardRestore.putsBack(ClipboardRestore.LEFT_UNREADABLE))
        assertEquals(ClipboardRestore.RESTORED, ClipboardRestore.decide("Hi", "Hi"))
        assertEquals(ClipboardRestore.SKIPPED_CHANGED, ClipboardRestore.decide("other", "Hi"))
    }

    @Test fun theChecksWaitAboutASecondAndSettleFirst() {
        val clipboard = FakeClipboard("mine")
        val field = CheckedField("", landsOn = emptySet(), setTextWorks = false, clipboard = clipboard)
        val sleeps = mutableListOf<Long>()
        runBlocking { DictationPaste.insert(clipboard, field, "Hi", sleep = { sleeps += it }) { null } }
        assertEquals("settles before pasting", DictationPaste.SETTLE_MS, sleeps.first())
        val checks = sleeps.drop(1)
        assertTrue("polled every ${DictationPaste.POLL_MS} ms", checks.all { it == DictationPaste.POLL_MS })
        assertTrue("about a second of checks for each of the two pastes: ${checks.sum()}", checks.sum() in 2 * DictationPaste.CHECK_MS..2 * DictationPaste.CHECK_MS + 2 * DictationPaste.POLL_MS)
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
        val clipboard = FakeClipboard("keep me")
        var fallbackUsed = false

        val result = runBlocking {
            DictationPaste.insert(clipboard, object : FieldPort {
                var text = ""
                override fun paste(): Boolean {
                    text = "Hi"
                    return true
                }
                override fun setText(text: String, cursor: Int): Boolean {
                    fallbackUsed = true
                    return true
                }
                override fun readText(): String = text
            }, "Hi", sleep = {}) {
                fallbackUsed = true
                "Hi" to 2
            }
        }

        assertEquals(InsertMethod.PASTE, result.method)
        assertEquals(false, fallbackUsed)
    }

    /** A field for the direct write: [setTextLands] says whether set-text really changes it. */
    private class DirectField(
        var text: String,
        val setTextWorks: Boolean = true,
        val setTextLands: Boolean = true,
        val readable: Boolean = true,
        val clipboard: FakeClipboard,
    ) : FieldPort {
        var pastes = 0
        var setTexts = 0

        override fun paste(): Boolean {
            pastes++
            text += clipboard.contents.orEmpty()
            return true
        }

        override fun setText(text: String, cursor: Int): Boolean {
            setTexts++
            if (setTextWorks && setTextLands) this.text = text
            return setTextWorks
        }

        override fun readText(): String? = if (readable) text else null
    }

    private fun direct(field: DirectField, clipboard: FakeClipboard, empty: Boolean = true): Pair<PasteResult, ClipboardRestore?> = runBlocking {
        var restore: ClipboardRestore? = null
        val result = DictationPaste.insertIntoField(clipboard, field, "Hi there", empty, onRestore = { restore = it }, sleep = {}) {
            "Hi there" to 8
        }
        result to restore
    }

    @Test fun anEmptyFieldIsWrittenStraightInWithoutTheClipboard() {
        val clipboard = FakeClipboard("mine")
        // WhatsApp reports its placeholder as the text; the field is still empty.
        val field = DirectField("Message", clipboard = clipboard)
        val (result, restore) = direct(field, clipboard)
        assertEquals(PasteResult(InsertMethod.SET_TEXT, PasteCheck.DIRECT), result)
        assertEquals("Hi there", field.text)
        assertEquals("no paste, so no clipboard message", 0, field.pastes)
        assertEquals("the clipboard is untouched", "mine", clipboard.contents)
        assertEquals(ClipboardRestore.NOT_USED, restore)
    }

    @Test fun aWriteThatDoesntLandFallsBackToPasting() {
        val clipboard = FakeClipboard("mine")
        val field = DirectField("", setTextLands = false, clipboard = clipboard)
        val (result, _) = direct(field, clipboard)
        assertEquals(InsertMethod.PASTE, result.method)
        assertEquals(PasteCheck.LANDED, result.check)
        assertTrue(result.note!!.contains("didn't land, pasted instead"))
        assertEquals("only once in the field", "Hi there", field.text)
    }

    @Test fun aFieldThatRefusesTheWriteIsPasted() {
        val clipboard = FakeClipboard("mine")
        val field = DirectField("", setTextWorks = false, clipboard = clipboard)
        val (result, _) = direct(field, clipboard)
        assertEquals(PasteResult(InsertMethod.PASTE, PasteCheck.LANDED, result.note), result)
        assertEquals(1, field.pastes)
    }

    @Test fun anUnreadableFieldIsWrittenOnceAndNeverPasted() {
        val clipboard = FakeClipboard("mine")
        val field = DirectField("", readable = false, clipboard = clipboard)
        val (result, _) = direct(field, clipboard)
        assertEquals(PasteResult(InsertMethod.SET_TEXT, PasteCheck.DIRECT_UNCHECKED), result)
        assertEquals(0, field.pastes)
        assertEquals(1, field.setTexts)
    }

    @Test fun aFieldWithTextIsAlwaysPasted() {
        val clipboard = FakeClipboard("mine")
        val field = DirectField("Hello ", clipboard = clipboard)
        val (result, _) = direct(field, clipboard, empty = false)
        assertEquals(PasteResult(InsertMethod.PASTE, PasteCheck.LANDED), result)
        assertEquals("never rewritten with set-text", 0, field.setTexts)
        assertEquals("Hello Hi there", field.text)
    }

    @Test fun pieceAddsSpacesOnlyWhereWordsWouldRunTogether() {
        assertEquals("Hi", TextInsertion.pieceFor("", 0, 0, " Hi "))
        assertEquals(" there", TextInsertion.pieceFor("Hello", 5, 5, "there"))
        assertEquals("there ", TextInsertion.pieceFor("world", 0, 0, "there"))
        assertEquals("there", TextInsertion.pieceFor("Hello ", 6, 6, "there"))
        assertEquals("small", TextInsertion.pieceFor("Hello big world", 6, 9, "small").trim())
    }
}
