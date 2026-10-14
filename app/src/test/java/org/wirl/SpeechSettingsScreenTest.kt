package org.wirl

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.wirl.databinding.ItemWhisperModelBinding

/** The speech model section of Settings, as the screen draws it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeechSettingsScreenTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val savedFactory = Prefs.secureFactory

    @Before fun setUp() {
        // Robolectric has no Android keystore, so the API key store is kept in memory for these tests.
        Prefs.secureFactory = { app -> app.getSharedPreferences("test_secure", Context.MODE_PRIVATE) }
        ModelDownloads.clear()
        Prefs(context).whisperModel = WhisperModel.FAST
        WhisperApp.store(context).delete(WhisperModel.FAST.spec)
    }

    @After fun tearDown() {
        Prefs.secureFactory = savedFactory
        ModelDownloads.clear()
        WhisperApp.store(context).delete(WhisperModel.FAST.spec)
    }

    /** Runs the screen's queued work, then calls [check] with the activity. */
    private fun withSettings(check: (MainActivity) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                shadowOf(Looper.getMainLooper()).idle()
                check(activity)
            }
        }
    }

    private fun row(activity: MainActivity, id: Int): ItemWhisperModelBinding =
        ItemWhisperModelBinding.bind(activity.findViewById(id))

    @Test fun theFastModelShowsADownloadButtonWhenNothingIsDownloaded() {
        withSettings { activity ->
            val fast = row(activity, R.id.modelFast)
            assertEquals(View.VISIBLE, fast.download.visibility)
            assertEquals(View.GONE, fast.delete.visibility)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.modelBanner).visibility)
            assertEquals("Not downloaded", fast.status.text.toString())
        }
    }

    @Test fun theDownloadButtonIsReachableFromTheTopOfSettings() {
        // The banner's button starts the same download as the Fast row's button.
        withSettings { activity ->
            assertEquals(View.VISIBLE, activity.findViewById<Button>(R.id.bannerDownload).visibility)
        }
    }

    @Test fun aDownloadedModelHidesDownloadAndShowsDelete() {
        WhisperApp.store(context).fileFor(WhisperModel.FAST.spec).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(WhisperModel.FAST.sizeBytes.toInt()))
        }
        withSettings { activity ->
            val fast = row(activity, R.id.modelFast)
            assertEquals(View.GONE, fast.download.visibility)
            assertEquals(View.VISIBLE, fast.delete.visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.modelBanner).visibility)
        }
    }
}
