package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechGateTest {
    @Test fun silenceAndNoiseOnlyRecordingInsertsNothingAndCallsNoClaude() {
        for (silence in listOf("", "   ", "[BLANK_AUDIO]", "(silence) *sighs*", "Thanks for watching!")) {
            val spoken = SpeechGate.spokenWords(silence)
            assertFalse("no Claude call for: $silence", SpeechGate.callsClaude(spoken))
        }
    }

    @Test fun realSpeechCallsClaude() {
        assertTrue(SpeechGate.callsClaude(SpeechGate.spokenWords("[beep] see you at 6")))
    }

    @Test fun anEmptyReplyInsertsNothing() {
        assertNull("an empty reply inserts nothing", SpeechGate.textToInsert("hello", aiEnabled = true, cleaned = Result.success("")))
        assertNull(SpeechGate.textToInsert("hello", aiEnabled = true, cleaned = Result.success("   ")))
    }

    @Test fun aCleanedReplyIsInserted() {
        assertEquals("See you at 6.", SpeechGate.textToInsert("see you at 6", aiEnabled = true, cleaned = Result.success("See you at 6.")))
    }

    @Test fun aFailedCleanupOrCleanupOffInsertsTheSpokenWords() {
        assertEquals("see you at 6", SpeechGate.textToInsert("see you at 6", aiEnabled = true, cleaned = Result.failure(RuntimeException())))
        assertEquals("see you at 6", SpeechGate.textToInsert("see you at 6", aiEnabled = false, cleaned = null))
    }

    @Test fun anAnswerToTheSpeakerIsNotInserted() {
        // A real report: the dictation was itself a request.
        val heard = "Rewrite the Communication Activities Authoring Guide section. Here's what we currently have. In our draft."
        val reply = "I can't rewrite the Communication Activities Authoring Guide section yet because the draft text didn't come through. " +
            "Your message only contains the instruction, with no content after \"Here's what we currently have. In our draft.\" " +
            "Please paste the section you want revised, and I'll return the cleaned-up version."
        assertTrue(SpeechGate.isReplyToSpeaker(heard, reply))
        assertEquals(DictationOutcome.Spoken(heard), DictationDecision.decide(heard, aiEnabled = true, cleaned = Result.success(reply)))
        for ((said, answer) in listOf(
            "write me a short poem about the sea" to "Sure! Here's a short poem about the sea:\n\nWaves roll in beneath a silver sky,\nThe gulls call out as tides go by.",
            "what's the capital of france" to "The capital of France is Paris, which is also its largest city and home to about two million people.",
            "summarize this email for me" to "I'd be happy to help, but I need the email text first. Could you paste it here?",
        )) {
            assertTrue(answer, SpeechGate.isReplyToSpeaker(said, answer))
        }
    }

    @Test fun normalCleanupsAreKept() {
        for ((said, cleaned) in listOf(
            "I can't make it tonight, sorry, um, maybe tomorrow" to "I can't make it tonight, sorry. Maybe tomorrow?",
            "Is it still a pro, juicing that spacebar thing" to "Is it still producing that spacebar thing?",
            "sure here's the plan we meet at five no actually six" to "Sure, here's the plan: we meet at six.",
            "please paste the report into the doc before you send it" to "Please paste the report into the doc before you send it.",
            "could you send me the the file uh when you get a chance" to "Could you send me the file when you get a chance?",
            "Rewrite the Communication Activities Authoring Guide section. Here's what we currently have. In our draft." to
                "Rewrite the Communication Activities Authoring Guide section. Here's what we currently have in our draft:",
            "buy milk eggs bread new line call mom make that a list" to "- Buy milk\n- Eggs\n- Bread\n\nCall Mom",
            "their going too the store accept on sunday" to "They're going to the store except on Sunday.",
            "I don’t think so" to "I don't think so.",
            "我哋聽日去飲茶" to "我們明天去飲茶。",
            "ok" to "Okay.",
        )) {
            assertFalse(cleaned, SpeechGate.isReplyToSpeaker(said, cleaned))
        }
    }

    @Test fun aShortAnswerToASpokenQuestionIsCaught() {
        // From the user's Diagnostics: the question was answered instead of typed.
        assertTrue(SpeechGate.isReplyToSpeaker("What is the abbreviation for Italy?", "IT"))
        assertTrue(SpeechGate.isReplyToSpeaker("What's the capital of France?", "Paris."))
        assertTrue(SpeechGate.isReplyToSpeaker("Who wrote the play Hamlet?", "Shakespeare."))
    }

    @Test fun shortCleanupsAreNotMistakenForAnswers() {
        // Self-correction, filler, commands and numbers keep the speaker's words.
        assertFalse(SpeechGate.isReplyToSpeaker("Meet at three, no wait, four.", "Meet at four."))
        assertFalse(SpeechGate.isReplyToSpeaker("Um so yeah I think so", "Yeah, I think so."))
        assertFalse(SpeechGate.isReplyToSpeaker("three hundred and twenty five dollars", "$325"))
        assertFalse(SpeechGate.isReplyToSpeaker("eggs milk bread make that a list", "- Eggs\n- Milk\n- Bread"))
        assertFalse(SpeechGate.isReplyToSpeaker("I'll meat you at the bark", "I'll meet you at the park."))
        // A dictionary spelling isn't a new word.
        assertFalse(SpeechGate.isReplyToSpeaker("I love chat gee pee tee", "I love ChatGPT", listOf("ChatGPT")))
        assertFalse("too few words said to judge", SpeechGate.isReplyToSpeaker("Sounds good", "Sounds good!"))
    }

    @Test fun shortCleanEnglishIsTypedAsHeard() {
        assertTrue(SpeechGate.typesAsHeard("What is the abbreviation for Italy?", dictionaryHit = false))
        assertTrue(SpeechGate.typesAsHeard("Sounds good, see you at six.", dictionaryHit = false))
    }

    @Test fun anythingCleanupWouldChangeStillGoesToCleanup() {
        assertFalse("filler", SpeechGate.typesAsHeard("Um, see you at six.", dictionaryHit = false))
        assertFalse("self-correction", SpeechGate.typesAsHeard("See you at six, no wait, seven.", dictionaryHit = false))
        assertFalse("spoken command", SpeechGate.typesAsHeard("See you soon question mark", dictionaryHit = false))
        assertFalse("new paragraph", SpeechGate.typesAsHeard("Thanks new paragraph Bye", dictionaryHit = false))
        assertFalse("repeat", SpeechGate.typesAsHeard("I I think so.", dictionaryHit = false))
        assertFalse("dictionary word", SpeechGate.typesAsHeard("Send it to Sela.", dictionaryHit = true))
        assertFalse("too long", SpeechGate.typesAsHeard("One two three four five six seven eight nine ten eleven twelve thirteen.", dictionaryHit = false))
        assertFalse("Chinese", SpeechGate.typesAsHeard("试下系咪真系得先", dictionaryHit = false))
    }
}
