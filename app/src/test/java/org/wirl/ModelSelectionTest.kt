package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSelectionTest {
    @Test fun selectingADownloadedModelUsesWhisperWithIt() {
        val use = EngineChoice.use(DictationEngine.WHISPER, modelReady = true, nativeLoads = true)
        assertEquals(DictationEngine.WHISPER, use.engine)
    }

    @Test fun selectingAModelThatIsNotDownloadedFallsBackToGoogleWithTheReason() {
        // The Accurate model is picked but not downloaded: dictation uses Google, and the reason says why.
        val use = EngineChoice.use(DictationEngine.WHISPER, modelReady = false, nativeLoads = true)
        assertEquals(DictationEngine.GOOGLE, use.engine)
        assertEquals(FallbackReason.MODEL_MISSING, use.reason)
    }

    @Test fun theGoogleChoiceIsNeverAFallback() {
        val use = EngineChoice.use(DictationEngine.GOOGLE, modelReady = true, nativeLoads = true)
        assertEquals(DictationEngine.GOOGLE, use.engine)
        assertEquals(FallbackReason.NONE, use.reason)
    }

    @Test fun selectingAModelIsWhatDecidesTheEngineNotTheOtherModelsOnDisk() {
        // Only Fast is downloaded. Choosing Accurate, which isn't, must not use Fast's file.
        val fastReady = true
        val accurateReady = false
        assertEquals(DictationEngine.WHISPER, EngineChoice.choose(DictationEngine.WHISPER, fastReady, true))
        assertEquals(DictationEngine.GOOGLE, EngineChoice.choose(DictationEngine.WHISPER, accurateReady, true))
        assertTrue(WhisperModel.FAST.spec.fileName != WhisperModel.ACCURATE.spec.fileName)
    }
}
