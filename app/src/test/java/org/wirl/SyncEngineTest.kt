package org.wirl

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The whole phone side: paired computers, a sync round per mailbox, the dictionary, status lines and Diagnostics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncEngineTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val savedFactory = Prefs.secureFactory
    private val relay = FakeRelay()
    private val secret = SyncCrypto.newSecret()
    private val keys = SyncCrypto.derive(secret)
    private val qr = SyncProtocol.PairingQr("DESKTOP-ABC", "https://wirl-relay.wirl.workers.dev", secret)
    private val desktop = SyncProtocol.Device("0c1a3a0e-8a57-4b0e-9d63-0a2b1d2e3f40", "DESKTOP-ABC", SyncProtocol.KIND_COMPUTER, 0)

    @Before fun setUp() {
        Prefs.secureFactory = { app -> app.getSharedPreferences("test_secure", Context.MODE_PRIVATE) }
        Prefs(context).pairedComputers = emptyList()
        Prefs(context).dictionary = emptyList()
        DictionaryRepository.get(context).reload()
        PairedComputers.load(context)
    }

    @After fun tearDown() {
        Prefs(context).pairedComputers = emptyList()
        PairedComputers.load(context)
        Prefs.secureFactory = savedFactory
    }

    private fun id(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

    private fun sync(clock: () -> Long = System::currentTimeMillis) =
        DictionarySyncEngine.syncAll(context, { relay }, clock)

    private fun saved() = Prefs(context).pairedComputers.single()

    private fun line(c: PairedComputer) = SyncStatusText.line(context, c, SyncStatus.of(c, false), System.currentTimeMillis())

    /** The computer's side: what it has, synced into the mailbox. */
    private fun computerHas(vararg terms: Pair<Int, String>) {
        val now = System.currentTimeMillis()
        SyncRound.run(relay, keys, terms.map { (n, t) -> DictionaryEntry(t, emptyList(), "", id(n), now - 1000, false) }, desktop, now)
    }

    @Test fun pairingAndSyncingMergesBothDictionariesAndRecordsTheTime() {
        computerHas(1 to "Kubernetes")
        DictionaryRepository.get(context).update { listOf(DictionaryEntry("Sela")) }
        PairedComputers.pair(context, qr)

        assertTrue(sync())

        assertEquals(listOf("Kubernetes", "Sela"), DictionaryRepository.get(context).entries.value.map { it.term }.sorted())
        assertEquals("the computer gets the phone's word", setOf("Kubernetes", "Sela"), relay.read(keys)!!.entries.map { it.term }.toSet())
        val c = saved()
        assertTrue(c.lastSyncAt > 0)
        assertEquals("", c.status)
        assertEquals("Synced with DESKTOP-ABC just now", line(c))
        assertEquals(setOf("DESKTOP-ABC", DictionarySyncEngine.phoneName(context)), c.devices.map { it.name }.toSet())
        assertEquals("phone", relay.read(keys)!!.devices.single { it.id == Prefs(context).syncPhoneId }.kind)
        assertFalse(DictionarySyncEngine.running.value)
    }

    @Test fun anEditMadeDuringTheRoundIsKept() {
        computerHas(1 to "Kubernetes")
        PairedComputers.pair(context, qr)
        // The user adds a word while the round is uploading.
        relay.beforePut = { DictionaryRepository.get(context).update { it + DictionaryEntry("Zoe") } }
        sync()
        assertEquals(listOf("Kubernetes", "Zoe"), DictionaryRepository.get(context).entries.value.map { it.term }.sorted())
    }

    @Test fun aClosedMailboxAsksToPairAgain() {
        computerHas(1 to "Kubernetes")
        relay.close(keys)
        PairedComputers.pair(context, qr)
        assertTrue("pairing problems aren't retried in the background", sync())
        val c = saved()
        assertTrue(c.needsPairing)
        assertEquals("Pairing is no longer valid. Pair again.", line(c))
        assertTrue(Prefs(context).diagnostics.last().engines.startsWith("SYNC DESKTOP-ABC"))
    }

    @Test fun noConnectionSaysCantReachAndIsRetried() {
        PairedComputers.pair(context, qr)
        relay.failNext = RelayError.Network(java.io.IOException("Unable to resolve host"))
        assertFalse(sync())
        assertEquals("Can't reach the sync service. Will try again.", line(saved()))
        assertEquals(
            "the Dictionary rows name the computer",
            "DESKTOP-ABC: Can't reach the sync service. Will try again.",
            SyncStatusText.line(context, saved(), SyncStatus.of(saved(), false), 0, withName = true),
        )
        // The next round works and clears the message.
        assertTrue(sync())
        assertEquals("", saved().status)
    }

    @Test fun scanningTheSameCodeAgainReplacesTheComputer() {
        PairedComputers.pair(context, qr)
        PairedComputers.update(context, saved().key) { it.copy(status = SyncProblem.NEEDS_PAIRING.id) }
        PairedComputers.pair(context, qr.copy(name = "DESKTOP-NEW"))
        assertEquals(listOf("DESKTOP-NEW"), Prefs(context).pairedComputers.map { it.name })
        assertFalse(saved().needsPairing)
        PairedComputers.pair(context, qr.copy(secret = SyncCrypto.newSecret(), name = "Laptop"))
        assertEquals("another group is added", 2, Prefs(context).pairedComputers.size)
        PairedComputers.unpair(context, Prefs(context).pairedComputers.first { it.name == "DESKTOP-NEW" }.key)
        assertEquals(listOf("Laptop"), Prefs(context).pairedComputers.map { it.name })
    }

    @Test fun twoComputersAreTwoMailboxes() {
        val otherSecret = SyncCrypto.newSecret()
        PairedComputers.pair(context, qr)
        PairedComputers.pair(context, qr.copy(name = "Laptop", secret = otherSecret))
        DictionaryRepository.get(context).update { listOf(DictionaryEntry("Sela")) }
        assertTrue(sync())
        assertEquals(listOf("Sela"), relay.read(keys)!!.entries.map { it.term })
        assertEquals(listOf("Sela"), relay.read(SyncCrypto.derive(otherSecret))!!.entries.map { it.term })
    }

    @Test fun wiFiPairingsAreDroppedOnceWithANotice() {
        val v1 = JSONArray().put(JSONObject().put("deviceId", desktop.id).put("name", "OLD").put("secret", secret).put("port", 47913))
        context.getSharedPreferences("test_secure", Context.MODE_PRIVATE).edit()
            .putString(Prefs.KEY_PAIRED_COMPUTERS_V1, v1.toString()).commit()
        val prefs = Prefs(context)
        prefs.syncRepairNotice = false

        assertTrue(prefs.pairedComputers.isEmpty())
        assertTrue(prefs.syncRepairNotice)
        prefs.syncRepairNotice = false
        assertTrue(Prefs(context).pairedComputers.isEmpty())
        assertFalse("shown once", Prefs(context).syncRepairNotice)
    }

    @Test fun otherPhonesAreListed() {
        val other = SyncProtocol.Device("11111111-1111-4111-8111-111111111111", "Pixel 7", SyncProtocol.KIND_PHONE, 0)
        SyncRound.run(relay, keys, emptyList(), other, System.currentTimeMillis())
        computerHas()
        PairedComputers.pair(context, qr)
        sync()
        assertEquals(listOf("Pixel 7"), SyncStatus.otherPhones(saved(), Prefs(context).syncPhoneId))
    }
}
