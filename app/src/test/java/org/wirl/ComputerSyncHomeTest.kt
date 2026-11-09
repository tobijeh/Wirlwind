package org.wirl

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.TextView
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

/** The Computer sync row on the Settings Home list, and the screen it opens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComputerSyncHomeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val savedFactory = Prefs.secureFactory

    @Before fun setUp() {
        Prefs.secureFactory = { app -> app.getSharedPreferences("test_secure", Context.MODE_PRIVATE) }
        Prefs(context).setupShown = true
    }

    @After fun tearDown() {
        Prefs(context).pairedComputers = emptyList()
        PairedComputers.load(context)
        Prefs.secureFactory = savedFactory
    }

    private fun paired(vararg computers: PairedComputer) {
        Prefs(context).pairedComputers = computers.toList()
        PairedComputers.load(context)
    }

    private fun computer(lastSyncAt: Long = 0, needsPairing: Boolean = false, lastError: String = "") = PairedComputer(
        "0c1a3a0e-8a57-4b0e-9d63-0a2b1d2e3f40", "DESKTOP-ABC", SyncCrypto.newSecret(), 47913, listOf("192.168.1.2"),
        lastSyncAt = lastSyncAt, needsPairing = needsPairing, lastError = lastError,
    )

    private fun withHome(check: (MainActivity) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                shadowOf(Looper.getMainLooper()).idle()
                check(activity)
            }
        }
    }

    private fun summary(activity: MainActivity) = activity.findViewById<TextView>(R.id.computerSyncSummary)

    @Test fun theRowSitsRightAfterDictionaryAndInvitesPairing() {
        paired()
        withHome { activity ->
            val row = activity.findViewById<View>(R.id.rowComputerSync)
            assertEquals(View.VISIBLE, row.visibility)
            val list = row.parent as android.view.ViewGroup
            assertEquals(list.indexOfChild(activity.findViewById(R.id.rowDictionary)) + 1, list.indexOfChild(row))
            assertEquals("Pair with your computer", summary(activity).text.toString())
        }
    }

    @Test fun aSyncedComputerShowsWhenItSynced() {
        paired(computer(lastSyncAt = System.currentTimeMillis() - 2 * 60_000))
        withHome { activity ->
            assertEquals("Synced with DESKTOP-ABC 2 min ago", summary(activity).text.toString())
            assertEquals(activity.getColor(R.color.settings_text_secondary), summary(activity).currentTextColor)
        }
    }

    @Test fun anUnreachableComputerIsAmber() {
        paired(computer(lastSyncAt = 1, lastError = SyncProblem.DIFFERENT_NETWORK.id))
        withHome { activity ->
            assertEquals("Can't reach DESKTOP-ABC", summary(activity).text.toString())
            assertEquals(activity.getColor(R.color.settings_warning), summary(activity).currentTextColor)
        }
    }

    @Test fun aComputerThatRefusedThePhoneNeedsPairingAgain() {
        paired(computer(needsPairing = true))
        withHome { activity ->
            assertEquals("DESKTOP-ABC needs pairing again", summary(activity).text.toString())
            assertEquals(activity.getColor(R.color.settings_warning), summary(activity).currentTextColor)
        }
    }

    @Test fun theComputerSyncScreenShowsACardPerComputer() {
        paired(computer(needsPairing = true))
        ActivityScenario.launch(ComputerSyncActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                shadowOf(Looper.getMainLooper()).idle()
                val cards = activity.findViewById<android.widget.LinearLayout>(R.id.computerCards)
                assertEquals(1, cards.childCount)
                val card = cards.getChildAt(0)
                assertEquals("DESKTOP-ABC", card.findViewById<TextView>(R.id.computerName).text.toString())
                assertEquals(View.VISIBLE, card.findViewById<View>(R.id.pairAgain).visibility)
                assertEquals(View.GONE, card.findViewById<View>(R.id.syncNow).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.noComputers).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pairComputer).visibility)
            }
        }
    }
}
