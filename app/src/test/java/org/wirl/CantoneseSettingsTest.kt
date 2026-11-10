package org.wirl

import android.content.Context
import android.os.Looper
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.wirl.databinding.ItemWhisperModelBinding

/** The Cantonese (粵語) card on the Dictation page. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CantoneseSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val savedFactory = Prefs.secureFactory

    @Before fun setUp() {
        Prefs.secureFactory = { app -> app.getSharedPreferences("test_secure", Context.MODE_PRIVATE) }
        Prefs(context).setupShown = true
        ModelDownloads.clear()
        WhisperApp.store(context).delete(WhisperModel.CANTONESE.spec)
    }

    @After fun tearDown() {
        Prefs(context).whisperModel = WhisperModel.FAST
        Prefs.secureFactory = savedFactory
        ModelDownloads.clear()
    }

    private fun withCard(check: (MainActivity, ItemWhisperModelBinding) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                shadowOf(Looper.getMainLooper()).idle()
                check(activity, ItemWhisperModelBinding.bind(activity.findViewById(R.id.modelCantonese)))
            }
        }
    }

    @Test fun theCardShowsItsNameDescriptionAndSize() {
        withCard { _, card ->
            assertEquals("Cantonese (粵語)", card.select.text.toString())
            assertEquals("Cantonese with English mixed in. Writes as you speak, in traditional characters.", card.description.text.toString())
            assertEquals(View.VISIBLE, card.download.visibility)
            assertTrue(card.download.text.toString().startsWith("Download ("))
            assertEquals("not chosen, so no message", View.GONE, card.notice.visibility)
        }
    }

    @Test fun choosingItBeforeItsDownloadedSaysWhatHappensAndOffersTheDownload() {
        Prefs(context).whisperModel = WhisperModel.CANTONESE
        withCard { activity, card ->
            assertTrue(card.select.isChecked)
            assertEquals(View.VISIBLE, card.notice.visibility)
            assertTrue(card.notice.text.toString().contains("Google's recognizer in Cantonese (Hong Kong)"))
            assertEquals(View.VISIBLE, card.download.visibility)
            // Unit tests can't load native libraries, so the reason given here is the engine; on a phone it's the download.
            assertTrue(activity.findViewById<android.widget.TextView>(R.id.engineStatus).text.toString().startsWith("Using Google's recognizer"))
            assertEquals("Cantonese (粵語)", activity.findViewById<android.widget.TextView>(R.id.dictationSummary).text.toString())
        }
    }

    @Test fun aDownloadInProgressCanBeCancelled() {
        ModelDownloads.start(WhisperModel.CANTONESE)
        ModelDownloads.update(WhisperModel.CANTONESE, 40)
        withCard { _, card ->
            assertEquals(View.VISIBLE, card.cancel.visibility)
            card.cancel.performClick()
            assertTrue(ModelDownloads.isCancelled(WhisperModel.CANTONESE))
        }
    }

    @Test fun unpackingShowsItsOwnStatusWithoutCancel() {
        ModelDownloads.start(WhisperModel.CANTONESE)
        ModelDownloads.update(WhisperModel.CANTONESE, 100)
        withCard { _, card ->
            assertEquals("Unpacking…", card.status.text.toString())
            assertEquals(View.GONE, card.cancel.visibility)
        }
    }
}
