package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptFilterTest {
    @Test fun bracketedSoundTagsAreRemovedAndTheWordsAroundThemKept() {
        assertEquals("see you at 6", TranscriptFilter.removeAnnotations("[beep] see you at 6"))
        assertEquals("Meet at noon", TranscriptFilter.removeAnnotations("Meet at [BLANK_AUDIO] noon"))
        assertEquals("the plan is set", TranscriptFilter.removeAnnotations("the plan [noise] is set"))
    }

    @Test fun parentheticalAndAsteriskSoundNotesAreRemoved() {
        assertEquals("ok then", TranscriptFilter.removeAnnotations("ok (music) then"))
        assertEquals("fine", TranscriptFilter.removeAnnotations("(silence) fine"))
        assertEquals("I need a minute", TranscriptFilter.removeAnnotations("*sighs* I need a minute"))
        assertEquals("the door", TranscriptFilter.removeAnnotations("(whooshing) the door"))
    }

    @Test fun realParentheticalWordsAreKept() {
        assertEquals("Ship it (see below) tomorrow", TranscriptFilter.removeAnnotations("Ship it (see below) tomorrow"))
        assertEquals("Call the office (phone number is in the file)", TranscriptFilter.removeAnnotations("Call the office (phone number is in the file)"))
        assertEquals("Ask about the [link] first", TranscriptFilter.removeAnnotations("Ask about the [link] first"))
    }

    @Test fun annotationsAloneLeaveNothing() {
        assertEquals("", TranscriptFilter.clean("[BLANK_AUDIO]"))
        assertEquals("", TranscriptFilter.clean("(silence) *sighs* [music]"))
    }

    @Test fun aSilenceHallucinationAloneIsDropped() {
        assertEquals("", TranscriptFilter.clean("Thanks for watching!"))
        assertEquals("", TranscriptFilter.clean("Thank you."))
        assertEquals("", TranscriptFilter.clean("Please subscribe"))
        assertEquals("", TranscriptFilter.clean("you"))
    }

    @Test fun theSamePhraseInsideALongerSentenceIsKept() {
        assertEquals("Thanks for watching the demo, it was great", TranscriptFilter.clean("Thanks for watching the demo, it was great"))
        assertEquals("Thank you for coming to the meeting", TranscriptFilter.clean("Thank you for coming to the meeting"))
        assertEquals("Thank you for the update", TranscriptFilter.clean("Thank you for the update"))
    }

    @Test fun realWordsAreUntouchedByTheFilter() {
        assertEquals("See you at 6", TranscriptFilter.clean("See you at 6"))
        assertEquals("line one\nline two", TranscriptFilter.clean("line one\nline two"))
    }
}
