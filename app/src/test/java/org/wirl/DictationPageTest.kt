package org.wirl

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The Google recognizer check lives in Developer options, and Setup's last item is the speech model download. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictationPageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val savedFactory = Prefs.secureFactory

    @Before fun setUp() {
        Prefs.secureFactory = { app -> app.getSharedPreferences("test_secure", Context.MODE_PRIVATE) }
        Prefs(context).setupShown = true
        Prefs(context).developerOptions = false
        Prefs(context).whisperModel = WhisperModel.FAST
        WhisperApp.store(context).delete(WhisperModel.FAST.spec)
    }

    @After fun tearDown() {
        Prefs(context).developerOptions = false
        WhisperApp.store(context).delete(WhisperModel.FAST.spec)
        Prefs.secureFactory = savedFactory
    }

    private fun isInside(view: View, ancestorId: Int): Boolean {
        var p = view.parent
        while (p is ViewGroup) {
            if (p.id == ancestorId) return true
            p = p.parent
        }
        return false
    }

    @Test fun theGoogleCheckIsInDeveloperOptionsNotOnTheDictationPage() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                shadowOf(Looper.getMainLooper()).idle()
                val button = activity.findViewById<TextView>(R.id.testDictation)
                assertEquals("Check Google recognizers", button.text.toString())
                assertTrue(isInside(button, R.id.developerCard))
                assertFalse(isInside(button, R.id.pageDictation))
                assertEquals("hidden while Developer options are off", View.GONE, button.visibility)
            }
        }
    }

    @Test fun theGoogleCheckShowsWithDeveloperOptions() {
        Prefs(context).developerOptions = true
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.testDictation).visibility)
                assertTrue(activity.findViewById<TextView>(R.id.testResults).text.toString().startsWith("Troubleshooting"))
            }
        }
    }

    @Test fun theMicrophoneCardShowsOnlyWhenTheMicrophoneIsNotAllowed() {
        val app = org.robolectric.Shadows.shadowOf(context as android.app.Application)
        app.denyPermissions(android.Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.micCard).visibility)
                assertEquals("Wirl can't use the microphone yet.", activity.findViewById<TextView>(R.id.micState).text.toString())
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.micButton).visibility)
            }
        }
        app.grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals("nothing to say once it's allowed", View.GONE, activity.findViewById<View>(R.id.micCard).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.cardDictation).visibility)
            }
        }
    }

    @Test fun advancedShowsTheEngineInUseWithoutAnEngineSelector() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals("no engine selector any more", 0, activity.resources.getIdentifier("engineChoice", "id", context.packageName))
                assertEquals("Speech recognition in use", activity.getString(R.string.advanced_engine_title))
                val status = activity.findViewById<TextView>(R.id.engineStatus).text.toString()
                assertTrue(status, status.startsWith("Using Google's recognizer as a backup, because the "))
                assertTrue(isInside(activity.findViewById(R.id.engineStatus), R.id.advancedBody))
                assertTrue(isInside(activity.findViewById(R.id.mobileDataSwitch), R.id.advancedBody))
            }
        }
    }

    @Test fun setupCanOpenTheDictationPage() {
        val intent = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_PAGE, MainActivity.PAGE_DICTATION)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pageDictation).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.pageHome).visibility)
            }
        }
    }

    @Test fun setupsLastItemIsTheSpeechModel() {
        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals("Speech model downloaded", activity.findViewById<TextView>(R.id.testStatus).text.toString())
                assertTrue(activity.findViewById<TextView>(R.id.modelDetail).text.toString().startsWith("Download English · Fast"))
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.openModels).visibility)
            }
        }
        assertEquals(false, SetupActivity.status(context)[SetupChecklist.Item.SPEECH_MODEL])
    }

    @Test fun aDownloadedModelTicksTheSetupItem() {
        val spec = WhisperModel.FAST.spec
        val file = WhisperApp.store(context).fileFor(spec)
        file.parentFile?.mkdirs()
        java.io.RandomAccessFile(file, "rw").use { it.setLength(spec.sizeBytes) }
        assertEquals(true, SetupActivity.status(context)[SetupChecklist.Item.SPEECH_MODEL])
        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals("English · Fast is downloaded and ready.", activity.findViewById<TextView>(R.id.modelDetail).text.toString())
                assertEquals(View.GONE, activity.findViewById<View>(R.id.openModels).visibility)
            }
        }
    }
}
