package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEngineTest {
    private val google = "com.google.android.googlequicksearchbox/com.google.android.voicesearch.serviceapi.GoogleRecognitionService"

    @Test fun onDeviceIsPreferredWhenAvailable() {
        assertEquals(SpeechEngine.Choice.ON_DEVICE, SpeechEngine.choose(onDeviceAvailable = true, googleAvailable = true, systemAvailable = true))
    }

    @Test fun onDeviceAloneIsEnough() {
        assertEquals(SpeechEngine.Choice.ON_DEVICE, SpeechEngine.choose(onDeviceAvailable = true, googleAvailable = false, systemAvailable = false))
    }

    @Test fun googleIsNextWhenThereIsNoOnDevice() {
        assertEquals(SpeechEngine.Choice.GOOGLE, SpeechEngine.choose(onDeviceAvailable = false, googleAvailable = true, systemAvailable = true))
    }

    @Test fun googleAloneIsEnough() {
        assertEquals(SpeechEngine.Choice.GOOGLE, SpeechEngine.choose(onDeviceAvailable = false, googleAvailable = true, systemAvailable = false))
    }

    @Test fun systemDefaultIsTheLastResort() {
        assertEquals(SpeechEngine.Choice.SYSTEM, SpeechEngine.choose(onDeviceAvailable = false, googleAvailable = false, systemAvailable = true))
    }

    @Test fun noneWhenNothingIsAvailable() {
        assertEquals(SpeechEngine.Choice.NONE, SpeechEngine.choose(onDeviceAvailable = false, googleAvailable = false, systemAvailable = false))
    }

    @Test fun detectionFollowsTheSameOrder() {
        assertEquals(
            SpeechEngine.Choice.GOOGLE,
            SpeechEngine.Detection(onDeviceAvailable = false, googleComponent = google, systemAvailable = true).choice,
        )
        assertEquals(
            SpeechEngine.Choice.NONE,
            SpeechEngine.Detection(onDeviceAvailable = false, googleComponent = null, systemAvailable = false).choice,
        )
    }

    @Test fun describeNamesTheEngineInUse() {
        assertEquals(
            "On-device recognizer (works offline)",
            SpeechEngine.describe(SpeechEngine.Detection(true, google, true)),
        )
        assertEquals(
            "Google speech service",
            SpeechEngine.describe(SpeechEngine.Detection(false, google, true)),
        )
        assertEquals(
            "Phone's default speech service",
            SpeechEngine.describe(SpeechEngine.Detection(false, null, true)),
        )
    }

    @Test fun describeListsWhatIsMissingWhenNothingWorks() {
        val text = SpeechEngine.describe(SpeechEngine.Detection(false, null, false))
        assertEquals("Not found. Missing: on-device recognizer, Google speech service, default speech service", text)
    }

    @Test fun describeListsOnlyWhatIsMissing() {
        val text = SpeechEngine.describe(SpeechEngine.Detection(false, null, false).copy(googleComponent = null, systemAvailable = false))
        assertTrue(text.endsWith("on-device recognizer, Google speech service, default speech service"))
    }

    @Test fun noneOnlyWhenNoEngineAtAll() {
        // An on-device recognizer alone still counts as available, so the message isn't shown.
        assertTrue(SpeechEngine.describe(SpeechEngine.Detection(true, null, false)).startsWith("On-device"))
    }
}
