package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Test

/** Cleanup output keeps its paragraphs and lists when it's inserted into the field. */
class MultilineInsertTest {
    private class Field(var buffer: String, var cursor: Int) : FieldPort {
        override fun paste(): Boolean {
            buffer = buffer.substring(0, cursor) + pasted + buffer.substring(cursor)
            cursor += pasted.length
            return true
        }
        override fun setText(text: String, cursor: Int): Boolean {
            buffer = text
            this.cursor = cursor
            return true
        }
        var pasted = ""
    }

    private class Clip : ClipboardPort<String> {
        private var contents: String? = null
        override fun current(): String? = contents
        override fun set(text: String) {
            contents = text
        }
        override fun restore(saved: String?) {
            contents = saved
        }
    }

    @Test fun paragraphsSurviveThePieceThatIsPasted() {
        val cleaned = "Thanks for the update.\n\nI'll review it tonight."
        assertEquals(cleaned, TextInsertion.pieceFor("", 0, 0, cleaned))
    }

    @Test fun aListKeepsItsLineBreaksThroughInsertion() {
        val cleaned = "We need:\n- eggs\n- milk\n- bread"
        val field = Field("", 0)
        val clip = Clip()
        val piece = TextInsertion.pieceFor(field.buffer, 0, 0, cleaned)
        DictationPaste.insert(clip, object : FieldPort by field {
            override fun paste(): Boolean {
                field.pasted = piece
                return field.paste()
            }
        }, piece) { null }
        assertEquals(cleaned, field.buffer)
        assertEquals("the field is not collapsed onto one line", 4, field.buffer.lines().size)
    }

    @Test fun insertingIntoTheMiddleOfTextKeepsTheLineBreaks() {
        val field = Field("Before. After.", 7)
        val piece = TextInsertion.pieceFor(field.buffer, 7, 7, "one\ntwo")
        field.pasted = piece
        field.paste()
        assertEquals("Before. one\ntwo After.", field.buffer)
    }
}
