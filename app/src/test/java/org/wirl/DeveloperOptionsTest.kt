package org.wirl

import android.content.Context
import android.os.Looper
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeveloperOptionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val savedFactory = Prefs.secureFactory

    @Before fun setUp() {
        // Robolectric has no Android keystore, so the API key store is kept in memory for these tests.
        Prefs.secureFactory = { app -> app.getSharedPreferences("test_secure", Context.MODE_PRIVATE) }
        Prefs(context).developerOptions = false
    }

    @After fun tearDown() {
        Prefs.secureFactory = savedFactory
        Prefs(context).developerOptions = false
    }

    @Test fun developerOptionsAreOffByDefaultAndHideDiagnostics() {
        assertFalse(Prefs(context).developerOptions)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(View.GONE, activity.findViewById<View>(R.id.openDiagnostics).visibility)
            }
        }
    }

    @Test fun turningDeveloperOptionsOnShowsDiagnostics() {
        Prefs(context).developerOptions = true
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.openDiagnostics).visibility)
            }
        }
    }

    @Test fun theDiagnosticsScreenDoesNotOpenWithDeveloperOptionsOff() {
        val activity = Robolectric.buildActivity(DiagnosticsActivity::class.java).create().get()
        assertTrue("the screen finishes itself", activity.isFinishing)
    }

    @Test fun theDiagnosticsScreenOpensWithDeveloperOptionsOn() {
        Prefs(context).developerOptions = true
        val activity = Robolectric.buildActivity(DiagnosticsActivity::class.java).create().get()
        assertFalse(activity.isFinishing)
    }

    @Test fun theLogKeepsRecordingWhileDeveloperOptionsAreOff() {
        // Logging doesn't depend on the switch: a problem is already in the log when the switch is turned on.
        val prefs = Prefs(context)
        assertFalse(prefs.developerOptions)
        prefs.diagnostics = DiagnosticsLog.add(prefs.diagnostics, DiagnosticEntry(1L, "whisper", null, "Inserted", null))
        assertEquals(1, Prefs(context).diagnostics.size)
        assertEquals("whisper", Prefs(context).diagnostics.single().engines)
    }

    @Test fun theLogKeepsOnlyTheLastTwentyEntries() {
        var entries = emptyList<DiagnosticEntry>()
        repeat(25) { entries = DiagnosticsLog.add(entries, DiagnosticEntry(it.toLong(), "e", null, "m$it", null)) }
        assertEquals(20, entries.size)
        assertEquals("m5", entries.first().message)
        assertEquals("m24", entries.last().message)
    }

    @Test fun theWhisperThreadCountDefaultsToFourAndIsKept() {
        assertEquals(4, Prefs(context).whisperThreads)
        Prefs(context).whisperThreads = 6
        assertEquals(6, Prefs(context).whisperThreads)
        Prefs(context).whisperThreads = 99
        assertEquals("clamped to 8", 8, Prefs(context).whisperThreads)
        Prefs(context).whisperThreads = 4
    }
}
