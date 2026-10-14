package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.wirl.SpeechEngine.Choice.GOOGLE_APP
import org.wirl.SpeechEngine.Choice.GOOGLE_TTS
import org.wirl.SpeechEngine.Choice.ON_DEVICE
import org.wirl.SpeechEngine.Choice.SYSTEM

class SpeechEngineTest {
    private val everything = SpeechEngine.Detection(
        onDeviceAvailable = true,
        googleAppComponent = "com.google.android.googlequicksearchbox/.Recognizer",
        googleTtsComponent = "com.google.android.tts/.Recognizer",
        systemAvailable = true,
    )

    @Test fun onlineOrderIsGoogleAppThenSpeechServicesThenSystem() {
        assertEquals(listOf(GOOGLE_APP, GOOGLE_TTS, SYSTEM), SpeechEngine.order(everything, onDevicePackInstalled = false))
    }

    @Test fun onDeviceComesFirstOnlyWhenItsPackIsInstalled() {
        assertEquals(listOf(ON_DEVICE, GOOGLE_APP, GOOGLE_TTS, SYSTEM), SpeechEngine.order(everything, onDevicePackInstalled = true))
    }

    @Test fun onDeviceIsNeverTheOnlyOption() {
        val onlyOnDevice = SpeechEngine.Detection(true, null, null, false)
        assertEquals(emptyList<SpeechEngine.Choice>(), SpeechEngine.order(onlyOnDevice, onDevicePackInstalled = false))
    }

    @Test fun missingServicesAreSkipped() {
        val noTts = everything.copy(googleTtsComponent = null)
        assertEquals(listOf(GOOGLE_APP, SYSTEM), SpeechEngine.order(noTts, onDevicePackInstalled = false))
    }

    @Test fun installedListIncludesOnDeviceEvenWithoutItsPack() {
        assertEquals(listOf(ON_DEVICE, GOOGLE_APP, GOOGLE_TTS, SYSTEM), SpeechEngine.installed(everything))
    }

    @Test fun onlyOnlineRecognizersPreferOfflineFalse() {
        assertTrue(SpeechEngine.preferOffline(ON_DEVICE))
        assertFalse(SpeechEngine.preferOffline(GOOGLE_APP))
        assertFalse(SpeechEngine.preferOffline(GOOGLE_TTS))
        assertFalse(SpeechEngine.preferOffline(SYSTEM))
    }

    @Test fun describeNamesTheFirstEngine() {
        assertEquals("Google app", SpeechEngine.describe(everything, onDevicePackInstalled = false))
        assertEquals("On-device recognizer", SpeechEngine.describe(everything, onDevicePackInstalled = true))
    }

    @Test fun describeListsWhatIsMissingWhenNothingWorks() {
        val none = SpeechEngine.Detection(false, null, null, false)
        assertEquals(
            "Not found. Missing: on-device recognizer, Google app, Speech Services by Google, default speech service",
            SpeechEngine.describe(none, onDevicePackInstalled = false),
        )
    }
}
