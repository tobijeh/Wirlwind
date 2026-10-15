package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperSettingsTest {
    @Test fun dictionaryTermsGoIntoTheInitialPrompt() {
        val prompt = WhisperPrompt.build(listOf("Tobias", "Kubernetes", "Wirl"))
        assertNotNull(prompt)
        assertTrue(prompt!!.contains("Tobias"))
        assertTrue(prompt.contains("Kubernetes"))
        assertTrue(prompt.contains("Wirl"))
    }

    @Test fun duplicatesAndBlankTermsAreDropped() {
        assertEquals("Glossary: Wirl, Tobias.", WhisperPrompt.build(listOf(" Wirl ", "", "Wirl", "Tobias")))
    }

    @Test fun noTermsMeansNoPrompt() {
        assertNull(WhisperPrompt.build(emptyList()))
        assertNull(WhisperPrompt.build(listOf("  ")))
    }

    @Test fun aLongDictionaryIsCutToTheLimitKeepingTheFirstTerms() {
        val terms = (1..200).map { "term$it" }
        val prompt = WhisperPrompt.build(terms)!!
        assertTrue(prompt.length <= WhisperPrompt.MAX_CHARS + "Glossary: .".length)
        assertTrue(prompt.contains("term1,") || prompt.contains("term1."))
        assertFalse(prompt.contains("term200"))
    }

    @Test fun theDefaultIsFourThreadsForThePerformanceCores() {
        assertEquals(4, WhisperPrompt.DEFAULT_THREADS)
        assertTrue(WhisperPrompt.THREAD_CHOICES.contains(4))
    }

    @Test fun englishModelsAlwaysUseEnglish() {
        assertEquals("en", WhisperPrompt.languageFor(WhisperModel.FAST, "de-DE"))
        assertEquals("en", WhisperPrompt.languageFor(WhisperModel.ACCURATE, "fr-FR"))
    }

    @Test fun multilingualUsesTheDictationLanguage() {
        assertEquals("de", WhisperPrompt.languageFor(WhisperModel.MULTILINGUAL, "de-DE"))
        assertEquals("auto", WhisperPrompt.languageFor(WhisperModel.MULTILINGUAL, ""))
    }

    @Test fun modelAndEngineKeysRoundTrip() {
        assertEquals(WhisperModel.ACCURATE, WhisperModel.fromKey(WhisperModel.ACCURATE.name))
        assertEquals(WhisperModel.FAST, WhisperModel.fromKey("nonsense"))
        assertEquals(DictationEngine.GOOGLE, DictationEngine.fromKey("google"))
        assertEquals(DictationEngine.WHISPER, DictationEngine.fromKey(null))
    }

    @Test fun whisperIsUsedOnlyWhenTheModelIsReadyAndTheLibraryLoads() {
        assertEquals(DictationEngine.WHISPER, EngineChoice.choose(DictationEngine.WHISPER, modelReady = true, nativeLoads = true))
        assertEquals(DictationEngine.GOOGLE, EngineChoice.choose(DictationEngine.WHISPER, modelReady = false, nativeLoads = true))
        assertEquals(DictationEngine.GOOGLE, EngineChoice.choose(DictationEngine.WHISPER, modelReady = true, nativeLoads = false))
        assertEquals(DictationEngine.GOOGLE, EngineChoice.choose(DictationEngine.GOOGLE, modelReady = true, nativeLoads = true))
    }

    @Test fun theFallbackReasonNamesWhyGoogleIsUsed() {
        assertEquals(FallbackReason.MODEL_MISSING, EngineChoice.use(DictationEngine.WHISPER, modelReady = false, nativeLoads = true).reason)
        assertEquals(FallbackReason.NATIVE_MISSING, EngineChoice.use(DictationEngine.WHISPER, modelReady = true, nativeLoads = false).reason)
        assertEquals(FallbackReason.NONE, EngineChoice.use(DictationEngine.WHISPER, modelReady = true, nativeLoads = true).reason)
        assertEquals(FallbackReason.NONE, EngineChoice.use(DictationEngine.GOOGLE, modelReady = false, nativeLoads = false).reason)
    }
}
