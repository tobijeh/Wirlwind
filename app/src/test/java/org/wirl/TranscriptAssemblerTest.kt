package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptAssemblerTest {
    @Test fun joinsFinalResultsInOrder() {
        val t = TranscriptAssembler()
        t.commit("hello there")
        t.commit("how are you")
        assertEquals("hello there how are you", t.text())
    }

    @Test fun partialResultShowsUntilItIsFinal() {
        val t = TranscriptAssembler()
        t.commit("hello")
        t.partial("there")
        assertEquals("hello there", t.text())
        t.commit("there friend")
        assertEquals("hello there friend", t.text())
    }

    @Test fun repeatedPartialDoesNotDuplicate() {
        val t = TranscriptAssembler()
        t.partial("hel")
        t.partial("hello")
        t.partial("hello wor")
        assertEquals("hello wor", t.text())
    }

    @Test fun blankResultsAreIgnored() {
        val t = TranscriptAssembler()
        t.commit("one")
        t.commit("   ")
        t.commit("")
        t.commit("two")
        assertEquals("one two", t.text())
    }

    @Test fun whitespaceIsTidied() {
        val t = TranscriptAssembler()
        t.commit("  hello   there ")
        t.commit("\tfriend\n")
        assertEquals("hello there friend", t.text())
    }

    @Test fun nothingSpokenIsEmpty() {
        assertEquals("", TranscriptAssembler().text())
    }
}
